import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080';

  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: apiProxyTarget,
          changeOrigin: true,
          xfwd: true,
        },
        '/actuator': {
          target: apiProxyTarget,
          changeOrigin: true,
          xfwd: true,
        },
        '/oauth2': {
          target: apiProxyTarget,
          changeOrigin: true,
          xfwd: true,
        },
        '/login/oauth2': {
          target: apiProxyTarget,
          changeOrigin: true,
          xfwd: true,
        },
      },
    },
    preview: {
      port: 4173,
      strictPort: true,
    },
  };
});
