import {expect, test} from '@playwright/test';
import {stubApi, chooseEnvironment} from './api';

/**
 * Switching environments has to change the DATA, not only the address.
 *
 * <p>THE BUG THIS PINS. Switching used to force a re-fetch by navigating to `/`
 * with `skipLocationChange` and back. But `/` redirects to `/tables`, so on the
 * tables page the bounce never left the route: Angular reused the component and
 * the rows never reloaded, while the picker and the address both showed the new
 * environment. Reported by Michael on 2026-09-01.
 *
 * <p>NOT a regression from putting `env` in the URL, though it looked like one.
 * The same assertion fails at the commit before that work, and `/schemas` — a
 * page where the bounce did change component — passes at every commit. Checked
 * both, in worktrees, rather than assumed.
 *
 * <p>That shape of bug is the worst available: nothing looks wrong. Every URL
 * assertion passed, which is exactly why these assert what the page shows.
 */
test.beforeEach(async ({page}) => {
  await stubApi(page);
});

test('switching environments reloads the rows on /schemas', async ({page}) => {
  await page.goto('/schemas');
  await expect(page.locator('.data-table tbody tr')).toHaveCount(2);

  await chooseEnvironment(page, 'staging');

  // the address moves...
  await expect(page).toHaveURL(/env=staging/);
  // ...and so does what is on the page
  await expect(page.locator('.data-table tbody tr')).toHaveCount(1);
  await expect(page.locator('.data-table tbody')).toContainText('staging_only');
});

test('and switching back reloads again', async ({page}) => {
  await page.goto('/schemas?env=staging');
  await expect(page.locator('.data-table tbody tr')).toHaveCount(1);

  await chooseEnvironment(page, 'regression');

  await expect(page).toHaveURL(/env=regression/);
  await expect(page.locator('.data-table tbody tr')).toHaveCount(2);
});

/**
 * THE PAGE THE BUG WAS REPORTED ON, and the reason the bounce was the wrong
 * mechanism: the root redirects here, so bouncing through `/` to re-instantiate
 * never left this route.
 */
test('switching environments reloads the rows on /tables, where the root lands', async ({page}) => {
  await page.goto('/tables');
  await expect(page.locator('.data-table tbody tr')).toHaveCount(2);

  await chooseEnvironment(page, 'staging');

  await expect(page).toHaveURL(/env=staging/);
  await expect(page.locator('.data-table tbody tr')).toHaveCount(1);
  await expect(page.locator('.data-table tbody')).toContainText('staging_only');
});
