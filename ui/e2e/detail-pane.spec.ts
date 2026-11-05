import {expect, test} from '@playwright/test';
import {ORDERS, stubApi} from './api';

/**
 * The query detail pane. Both assertions here pin a defect that reached a
 * person rather than a test.
 */
test.beforeEach(async ({page}) => {
  await stubApi(page);
});

/**
 * `profile.digest` is overwritten with the normalized digest when the profile
 * completes, so only the metrics keep the performance-schema one. The pane
 * read the field directly and labelled it `perf_schema`, showing the same
 * string as the `app norm` box beside it under the wrong name (sqlx-3cp).
 */
test('the two digest boxes show different digests under correct labels', async ({page}) => {
  await page.goto('/queries?q=orders&var-detail=1');
  await page.locator('.q-row').first().click();

  const boxes = page.locator('.digest-box');
  await expect(boxes).toHaveCount(2);

  const values = (await boxes.allTextContents()).map(text => text.trim().split(/\s+/)[0]);
  expect(values[0]).toBe(ORDERS.normalizedDigest);
  expect(values[1]).toBe(ORDERS.runtimeMetrics.digest);
  expect(values[0]).not.toBe(values[1]);
});

test('the perf_schema box carries the MySQL digest, not the v-prefixed one', async ({page}) => {
  await page.goto('/queries?q=orders&var-detail=1');
  await page.locator('.q-row').first().click();

  const perfBox = page.locator('.digest-box').nth(1);
  await expect(perfBox).toContainText(ORDERS.runtimeMetrics.digest);
  // the normalized digests carry a scheme version; a MySQL one never does
  await expect(perfBox).not.toContainText('v3:');
});

test('selecting a row shows its SQL', async ({page}) => {
  await page.goto('/queries?var-detail=1');
  await page.locator('.q-row', {hasText: 'sessions'}).click();

  await expect(page.locator('.detail-section').first()).toBeVisible();
  await expect(page.locator('.digest-box').first()).toContainText('v3:sessions');
});

/**
 * The pane is opt-in now: without it a row click opens the query's own page,
 * which is where people went anyway (sqlx-ywy.5).
 */
test('without the pane, clicking a row opens the query page', async ({page}) => {
  await page.goto('/queries');
  await expect(page.locator('.detail-pane')).toHaveCount(0);
  await page.locator('.q-row', {hasText: 'sessions'}).click();

  await expect(page).toHaveURL(/\/analyzed-sql\/v3:sessions/);
});
