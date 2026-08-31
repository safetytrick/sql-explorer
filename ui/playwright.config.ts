import {defineConfig, devices} from '@playwright/test';

const PORT = Number(process.env.E2E_PORT || 4173);

/**
 * Browser smoke tests over the PRODUCTION BUILD, with every backend call
 * stubbed at the network boundary.
 *
 * <p>The shape is the point. The suite this replaces needed the whole stack —
 * MySQL, Spring Boot, docker compose — so CI never ran it, and it sat
 * Protractor-era long after Protractor was gone. This one needs `npm run
 * build` and a browser, which makes it a step in the existing frontend job
 * rather than a new job with service containers (sqlx-6jy).
 *
 * <p>What it covers is what Karma cannot reach: the real router, real
 * navigation, and the URL round-trips this repo treats as an invariant —
 * every view must be a link.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['list']] : 'list',
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'on-first-retry'
  },
  projects: [{name: 'chromium', use: {...devices['Desktop Chrome']}}],
  webServer: {
    // Serves ui/build — so `npm run build` must have run. CI builds in the
    // step before this one, which is also what makes the suite test the
    // artifact that ships rather than a dev-server approximation.
    command: `node e2e/serve.mjs`,
    port: PORT,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000
  }
});
