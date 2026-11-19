import { defineConfig } from "vite";

// Tauri serves this dev server; build output goes to ../dist (frontendDist).
export default defineConfig({
  clearScreen: false,
  // Only VITE_-prefixed env vars reach the webview bundle.
  envPrefix: ["VITE_"],
  server: {
    port: 1420,
    strictPort: true,
    // Rust edits rebuild via cargo, not Vite — don't churn the watcher on src-tauri/target.
    watch: { ignored: ["**/src-tauri/**"] },
  },
  build: { target: "es2021", outDir: "dist", emptyOutDir: true },
});
