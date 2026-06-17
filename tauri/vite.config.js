import { defineConfig } from "vite";

// Tauri serves this dev server; build output goes to ../dist (frontendDist).
export default defineConfig({
  clearScreen: false,
  server: { port: 1420, strictPort: true },
  build: { target: "es2021", outDir: "dist", emptyOutDir: true },
});
