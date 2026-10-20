import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  workers: 2,
  reporter: [['list'], ['json', { outputFile: 'test-results/report.json' }]],
  use: { baseURL: 'http://127.0.0.1:4173', viewport: { width: 390, height: 844 }, screenshot: 'only-on-failure' },
  webServer: {
    command: 'npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
  },
})
