import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Vite outputs the built bundle directly into the Spring Boot server module's
// static-resource directory so a single `mvn package` produces a JAR (and Jib
// image) that serves both the API and the UI on port 8080.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: "../server/src/main/resources/static",
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    // For local dev (`npm run dev`): the SPA runs on Vite's :5173 and proxies
    // backend calls to the running Vex server on :8080.
    proxy: {
      "/collections": "http://localhost:8080",
      "/health": "http://localhost:8080",
      "/v3": "http://localhost:8080",
      "/swagger-ui.html": "http://localhost:8080",
      "/swagger-ui": "http://localhost:8080",
    },
  },
});
