import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: '/',
  plugins: [react()],
  build: {
    outDir: '../build/generated/resources/main/web',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false,
  },
});
