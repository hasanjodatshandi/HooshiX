import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/api/generated/**', 'src/main.tsx', 'src/test/**'],
      reporter: ['text', 'json-summary', 'lcov'],
      thresholds: {
        statements: 18,
        branches: 30,
        functions: 15,
        lines: 18,
        'src/errors/**': {
          statements: 90,
          branches: 95,
          functions: 85,
          lines: 90,
        },
        'src/state/appReducer.ts': {
          statements: 100,
          branches: 80,
          functions: 100,
          lines: 100,
        },
        'src/state/storage.ts': {
          statements: 90,
          branches: 80,
          functions: 100,
          lines: 90,
        },
        'src/validation/userInput.ts': {
          statements: 95,
          branches: 95,
          functions: 100,
          lines: 95,
        },
      },
    },
  },
})
