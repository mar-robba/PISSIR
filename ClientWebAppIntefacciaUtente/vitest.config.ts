import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    globals: true,
    include: ['src/**/*.test.{ts,tsx}', 'src/**/*.spec.{ts,tsx}']
  }
})
