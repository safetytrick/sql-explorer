import {expect, test} from '@playwright/test';
import {stubApi} from './api';

/**
 * The repo's central invariant: every view is a link. Paste a URL with
 * filters and get the matching view; change the filter and get the matching
 * URL.
 *
 * <p>`search-url.ts` is unit-tested as a codec, but nothing proved the codec
 * was wired to what renders — which is exactly the gap only a real router and
 * a real navigation can close (sqlx-6jy).
 */
test.beforeEach(async ({page}) => {
  await stubApi(page);
});

/**
 * The workbench's SQL-text filter.
 *
 * <p>Located by its wrapper class rather than a role: the input carries no
 * label element and no `type`, so there is nothing more semantic to hold on
 * to. If a test id is ever added, this is the one place to change.
 */
const sqlFilter = (page: import('@playwright/test').Page) => page.locator('.sql-search input');

test('a pasted URL filters the workbench', async ({page}) => {
  await page.goto('/queries?q=orders');

  await expect(page.locator('.q-row')).toHaveCount(1);
  await expect(page.locator('.q-row').first()).toContainText('orders');
  // and the control reflects it, rather than the filter applying invisibly
  await expect(sqlFilter(page)).toHaveValue('orders');
});

test('an unfiltered URL shows the whole corpus', async ({page}) => {
  await page.goto('/queries');

  await expect(page.locator('.q-row')).toHaveCount(3);
});

test('typing a filter writes it back to the URL', async ({page}) => {
  await page.goto('/queries');
  await expect(page.locator('.q-row')).toHaveCount(3);

  await sqlFilter(page).fill('sessions');

  await expect(page).toHaveURL(/[?&]q=sessions/);
  await expect(page.locator('.q-row')).toHaveCount(1);
});

/**
 * The round trip closed. A filter that only lived in component state would
 * survive a reload by accident or not at all; this proves the URL is the
 * source of truth.
 */
test('a filtered view survives a reload', async ({page}) => {
  await page.goto('/queries');
  await sqlFilter(page).fill('sessions');
  await expect(page).toHaveURL(/[?&]q=sessions/);

  await page.reload();

  await expect(page.locator('.q-row')).toHaveCount(1);
  await expect(page.locator('.q-row').first()).toContainText('sessions');
});

/**
 * Defaults are omitted so an untouched view keeps a clean URL — stated in
 * CLAUDE.md and easy to break by writing every parameter unconditionally.
 */
test('an untouched view keeps a clean URL', async ({page}) => {
  await page.goto('/queries');
  await expect(page.locator('.q-row')).toHaveCount(3);

  expect(new URL(page.url()).search).toBe('');
});

/**
 * A row's whole SQL is view state like any filter: expanding it writes
 * `var-expanded`, and a link carrying it opens with that row expanded
 * (sqlx-cnt).
 */
test('expanding a row shows its whole SQL and writes it to the URL', async ({page}) => {
  await page.goto('/queries?q=orders');
  const row = page.locator('.q-row').first();
  await expect(row.locator('.q-full-sql')).toHaveCount(0);

  await row.locator('.q-expand').click();

  await expect(row.locator('.q-full-sql')).toContainText('where id = ?');
  await expect(page).toHaveURL(/[?&]var-expanded=v3(:|%3A)orders/);
  await expect(page).toHaveURL(/[?&]q=orders/);
});

test('a link with an expanded row opens with it expanded', async ({page}) => {
  await page.goto('/queries?var-expanded=v3:users');

  await expect(page.locator('.q-row')).toHaveCount(3);
  await expect(page.locator('.q-full-sql')).toHaveCount(1);
  await expect(page.locator('.q-full-sql')).toContainText('`users`');

  await page.locator('.q-expand[aria-expanded="true"]').click();
  await expect(page.locator('.q-full-sql')).toHaveCount(0);
  expect(new URL(page.url()).searchParams.has('var-expanded')).toBe(false);
});
