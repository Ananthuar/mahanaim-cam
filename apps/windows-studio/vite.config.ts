import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

// https://vitejs.dev/config/
export default defineConfig({
    plugins: [react()],
    root: 'src/renderer',
    base: './',
    build: {
        outDir: resolve(__dirname, 'dist/renderer'),
        emptyOutDir: true,
    },
    server: {
        port: 5173,
        strictPort: true,
    },
    test: {
        globals: true,
        environment: 'jsdom',
        setupFiles: ['../../test/setup.ts'],
        include: ['../../test/**/*.test.{ts,tsx}'],
    },
    resolve: {
        alias: {
            '@': resolve(__dirname, 'src/renderer'),
        },
    },
});
