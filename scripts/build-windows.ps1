<#
.SYNOPSIS
    Build the Micro desktop client on Windows.

.DESCRIPTION
    Verifies the toolchain (MSVC, Rust, Node, WebView2), installs the JS dependencies and
    produces an NSIS installer plus the portable executable.

    Nothing is installed silently: MSVC, Rust and Node are pointed at their own installers.
    No cmake or nasm is needed on Windows — audiopus_sys links a prebuilt libopus for the
    MSVC target, and the vendored RNNoise is compiled by the cc crate with MSVC itself.

.PARAMETER Bundles
    Bundle formats to build: nsis (default), "nsis,msi", or "none" for just the .exe.

.EXAMPLE
    .\scripts\build-windows.ps1
.EXAMPLE
    .\scripts\build-windows.ps1 -Bundles nsis,msi
#>
[CmdletBinding()]
param(
    [string] $Bundles = "nsis"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir
$TauriDir  = Join-Path $RepoRoot "tauri"

# Building needs Node 18 (Vite 6); `npm test` needs 22 (the test runner expands globs only from 22).
$NodeMinMajor = 18
$NodeRecommendedMajor = 22

# --- Output ------------------------------------------------------------------
function Write-Step { param([string] $Message) Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Ok   { param([string] $Message) Write-Host "    v $Message" -ForegroundColor Green }
function Write-Info { param([string] $Message) Write-Host "    $Message" }
function Write-Warn { param([string] $Message) Write-Host "warning: $Message" -ForegroundColor Yellow }
function Stop-WithError {
    param([string] $Message)
    Write-Host "error: $Message" -ForegroundColor Red
    exit 1
}

function Test-Command {
    param([string] $Name)
    return [bool] (Get-Command $Name -ErrorAction SilentlyContinue)
}

# --- Toolchain checks --------------------------------------------------------
function Test-Msvc {
    Write-Step "Checking the MSVC build tools"
    if (Test-Command "link.exe") { Write-Ok "MSVC linker on PATH"; return }
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (Test-Path $vswhere) {
        $installed = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ($installed) { Write-Ok "Visual Studio C++ tools: $installed"; return }
    }
    Stop-WithError @"
The MSVC C++ build tools were not found - Rust's default Windows toolchain links with them,
and the vendored RNNoise/libopus are C code.

Install "Visual Studio Build Tools" with the "Desktop development with C++" workload:
  https://visualstudio.microsoft.com/visual-cpp-build-tools/
  (or: winget install --id Microsoft.VisualStudio.2022.BuildTools)
"@
}

function Test-Rust {
    Write-Step "Checking Rust"
    if (-not (Test-Command "cargo")) {
        Stop-WithError @"
cargo not found. Install Rust (MSVC toolchain) from https://rustup.rs
  winget install --id Rustlang.Rustup
Then open a new terminal and re-run this script.
"@
    }
    Write-Ok "$(rustc --version), $(cargo --version)"
}

function Test-Node {
    Write-Step "Checking Node.js"
    if (-not (Test-Command "node")) { Stop-WithError "node not found. Install Node $NodeRecommendedMajor+ from https://nodejs.org (or: winget install --id OpenJS.NodeJS.LTS)" }
    if (-not (Test-Command "npm"))  { Stop-WithError "npm not found - it ships with Node.js. Reinstall Node $NodeRecommendedMajor+." }
    $version = (node -v).TrimStart("v")
    $major = [int] ($version.Split(".")[0])
    if ($major -lt $NodeMinMajor) {
        Stop-WithError "Node $version is too old (Vite 6 needs >= $NodeMinMajor). Install Node $NodeRecommendedMajor+."
    }
    if ($major -lt $NodeRecommendedMajor) { Write-Warn "Node $version can build, but 'npm test' needs Node $NodeRecommendedMajor+ (glob support in the test runner)." }
    Write-Ok "node $(node -v), npm $(npm -v)"
}

# A running Micro holds an exclusive handle on its own .exe, so the linker cannot replace it and
# cargo fails late with a bare "os error 5". Windows forbids deleting or writing a running image
# but still allows *renaming* it, so move it aside: the link gets a free path and the instance the
# user is using keeps running (and playing audio) off the renamed file. The leftovers are swept on
# the next build, once nothing holds them any more.
function Unblock-TargetExe {
    Write-Step "Freeing the output executable"
    $dir = Join-Path $TauriDir "src-tauri\target\release"
    $exe = Join-Path $dir "micro-client.exe"

    # Sweep copies moved aside by earlier builds whose process has since exited.
    if (Test-Path $dir) {
        Get-ChildItem $dir -Filter "micro-client.locked*.exe" -ErrorAction SilentlyContinue | ForEach-Object {
            try { Remove-Item $_.FullName -Force -ErrorAction Stop } catch { }
        }
    }
    if (-not (Test-Path $exe)) { Write-Ok "nothing to free"; return }

    try {
        $stream = [System.IO.File]::Open($exe, 'Open', 'ReadWrite', 'None')
        $stream.Close()
        Write-Ok "not locked"
        return
    } catch { }

    # Locked. Pick a name no surviving leftover is using, and move it out of the way.
    $moved = $null
    foreach ($n in 1..20) {
        $candidate = Join-Path $dir "micro-client.locked$n.exe"
        if (Test-Path $candidate) { continue }
        try { Rename-Item -Path $exe -NewName (Split-Path $candidate -Leaf) -ErrorAction Stop; $moved = $candidate; break } catch { }
    }
    if (-not $moved) {
        $running = Get-Process -Name "micro-client" -ErrorAction SilentlyContinue
        $hint = if ($running) { "Quit Micro from the tray (right-click the Micro icon -> Quit), then re-run this script." }
                else { "Close whatever holds it (or wait for your antivirus to finish scanning it), then re-run this script." }
        Stop-WithError "$exe is locked and could not be moved aside. $hint"
    }

    Write-Ok "moved the locked build aside ($(Split-Path $moved -Leaf))"
    if (Get-Process -Name "micro-client" -ErrorAction SilentlyContinue) {
        Write-Warn "Micro is running: it keeps using the OLD build until you quit and relaunch it."
    }
}

function Test-WebView2 {
    Write-Step "Checking the WebView2 runtime"
    $keys = @(
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
        "HKLM:\SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
        "HKCU:\SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
    )
    foreach ($key in $keys) {
        if (Test-Path $key) {
            $version = (Get-ItemProperty -Path $key -ErrorAction SilentlyContinue).pv
            if ($version) { Write-Ok "WebView2 runtime $version"; return }
        }
    }
    Write-Warn "WebView2 runtime not detected. It is preinstalled on Windows 11 and current Windows 10; the app needs it at RUNTIME (not to build): https://developer.microsoft.com/microsoft-edge/webview2/"
}

# --- Build -------------------------------------------------------------------
function Install-JsDependencies {
    Write-Step "Installing JS dependencies"
    Push-Location $TauriDir
    try {
        if (Test-Path "package-lock.json") {
            # npm ci is reproducible but demands a lockfile in sync with package.json.
            npm ci
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "npm ci failed - falling back to npm install"
                npm install
                if ($LASTEXITCODE -ne 0) { Stop-WithError "npm install failed" }
            }
        } else {
            npm install
            if ($LASTEXITCODE -ne 0) { Stop-WithError "npm install failed" }
        }
    } finally { Pop-Location }
    Write-Ok "dependencies ready"
}

function Invoke-TauriBuild {
    Write-Step "Building the Micro client (bundles: $Bundles)"
    Write-Info "this compiles libopus and RNNoise from source - the first run takes a few minutes"
    Push-Location $TauriDir
    try {
        if ($Bundles -eq "none") {
            npm run tauri build -- --no-bundle
        } else {
            npm run tauri build -- --bundles $Bundles
        }
        if ($LASTEXITCODE -ne 0) { Stop-WithError "the build failed (see the output above)" }
    } finally { Pop-Location }
}

function Show-Outputs {
    Write-Step "Build artifacts"
    $release = Join-Path $TauriDir "src-tauri\target\release"
    $exe = Join-Path $release "micro-client.exe"
    if (Test-Path $exe) {
        $size = [math]::Round((Get-Item $exe).Length / 1MB, 1)
        Write-Info "$exe  ($size MB, portable)"
    }
    # Only what this run produced: the bundle directory keeps installers from earlier builds (and
    # earlier versions), and listing those here reads as "this build made them".
    $bundle = Join-Path $release "bundle"
    if (Test-Path $bundle) {
        Get-ChildItem -Path $bundle -Recurse -Include *.exe, *.msi -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTimeUtc -ge $script:BuildStarted } | ForEach-Object {
            $size = [math]::Round($_.Length / 1MB, 1)
            Write-Info "$($_.FullName)  ($size MB)"
        }
    }
    Write-Ok "done"
}

# --- Run ---------------------------------------------------------------------
Write-Host "Micro - desktop client build (Windows)" -ForegroundColor White
Write-Host ""

if (-not (Test-Path $TauriDir)) { Stop-WithError "cannot find $TauriDir - run this script from inside the repository." }

Test-Msvc
Test-Rust
Test-Node
Test-WebView2
Unblock-TargetExe

Install-JsDependencies
$script:BuildStarted = [DateTime]::UtcNow   # UTC: local time repeats an hour every DST fall-back
Invoke-TauriBuild
Show-Outputs

Write-Host ""
Write-Host "Virtual mic: " -ForegroundColor White -NoNewline
Write-Host "install VB-CABLE (https://vb-audio.com/Cable/); the client auto-selects `"CABLE Input`"."
Write-Host "Builds are unsigned, so SmartScreen may warn on first launch (More info -> Run anyway)."
