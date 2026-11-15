import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the dashboard calls /api on its own origin and Vite forwards it to the
// HookGuard API, so no CORS configuration is needed. In Docker, nginx does the same.
const apiTarget = process.env.HOOKGUARD_API_URL ?? 'http://localhost:8080';
const receiverTarget = process.env.HOOKGUARD_RECEIVER_URL ?? 'http://localhost:9090';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': apiTarget,
      // The live demo page reads the demo receiver's log and toggles its outage switch.
      '/receiver': { target: receiverTarget, rewrite: (path) => path.replace(/^\/receiver/, '') },
    },
  },
});
