import {expect, test} from '@playwright/test';
import {stubApi} from './api';

test.beforeEach(async ({page}) => {
  await stubApi(page);
});

test('the root opens on the schemas list, the starting page', async ({page}) => {
  await page.goto('/');

  await expect(page).toHaveURL(/\/schemas$/);
});

/**
 * An unknown URL gets a real not-found page, and the address that failed is
 * shown rather than swallowed.
 *
 * <p>Worth a test because the documentation had drifted from it twice over:
 * CLAUDE.md said there was no catch-all route and that a bad URL rendered an
 * empty outlet while resetting the address bar. Both halves were wrong, and
 * the drift was only visible from a real browser on a real route — which is
 * the gap this suite exists to close (sqlx-6jy).
 */
test('an unknown URL gets a not-found page that names the bad address', async ({page}) => {
  await page.goto('/no-such-page');

  await expect(page).toHaveURL(/\/no-such-page$/);
  await expect(page.locator('.view-title')).toContainText('Not found');
  await expect(page.locator('.card-empty').first()).toContainText('/no-such-page');
});

/** It offers a way out, so a stale link is not a dead end. */
test('the not-found page links back into the app', async ({page}) => {
  await page.goto('/no-such-page');

  await page.locator('app-not-found').getByRole('link', {name: 'Queries'}).click();

  await expect(page).toHaveURL(/\/queries/);
  await expect(page.locator('.q-row')).toHaveCount(3);
});

/** Deep links must survive a cold load, not only in-app navigation. */
test('a deep link loads directly', async ({page}) => {
  await page.goto('/unqueried');

  await expect(page.locator('.view-title')).toContainText('Unqueried tables');
});

test('the nav moves between the main views', async ({page}) => {
  await page.goto('/queries');
  await expect(page.locator('.q-row')).toHaveCount(3);

  await page.getByRole('link', {name: 'Unqueried', exact: true}).click();
  await expect(page).toHaveURL(/\/unqueried/);
  await expect(page.locator('.view-title')).toContainText('Unqueried tables');
});
