import { defineConfig } from 'vitest/config'
import path from 'node:path'

/*
 * Test configuration is kept out of vite.config.ts because Vite 6's own config type does not
 * carry a `test` field, and merging them means the build config no longer typechecks.
 */
export default defineConfig({
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
  },
})
