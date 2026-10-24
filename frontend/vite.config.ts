import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const backend = 'http://localhost:8080';
const proxied = ['/api', '/oauth2', '/login', '/logout', '/mcp', '/.well-known'];

export default defineConfig(({ mode }) => ({
  base: '/',
  plugins: [react()],
  server: {
    port: 5173,
    // In mock mode everything is answered in-browser by src/mock; otherwise forward to Spring Boot.
    proxy:
      mode === 'mock'
        ? undefined
        : Object.fromEntries(proxied.map((p) => [p, { target: backend, xfwd: true }])),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
}));
