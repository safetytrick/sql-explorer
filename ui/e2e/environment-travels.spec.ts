import {expect, test} from '@playwright/test';
import {stubApi} from './api';

/**
 * Which corpus a page is about belongs in the URL.
 *
 * <p>Until sqlx-29f the environment lived only in `localStorage`, so every
 * address in the application was relative to a corpus it did not name — and
 * this deployment's two environments share four schema names
 * (`information_schema`, `mysql`, `performance_schema`, `sys`), so a link to a
 * table in any of them silently resolved against whatever the recipient had
 * last picked. Getting it wrong produces no error: it produces real numbers
 * answering a different question.
 */
test.beforeEach(async ({page}) => {
  await stubApi(page);
});

test('a link naming an environment opens on it, whatever was stored', async ({page}) => {
  await page.goto('/');
  await page.evaluate(() => localStorage.setItem('sql-explorer.environment', 'regression'));

  await page.goto('/tables?env=staging');

  await expect(page.locator('.nav-environment-select').first()).toHaveValue('staging');
});

/**
 * The parameter is written ALWAYS, including when it names the default — a
 * deliberate break from omit-defaults, because the bare URL is precisely the
 * ambiguous case.
 */
test('picking an environment writes it to the address', async ({page}) => {
  await page.goto('/tables');
  await page.locator('.nav-environment-select').first().selectOption('staging');

  await expect(page).toHaveURL(/env=staging/);
});

test('the environment survives a navigation between pages', async ({page}) => {
  await page.goto('/tables?env=staging');
  await page.getByRole('link', {name: 'Queries', exact: true}).click();

  await expect(page).toHaveURL(/env=staging/);
});

/**
 * An unknown name is REPORTED, not substituted. The backend falls back to its
 * default for a name it does not have, which is right for a stale header and
 * wrong for a link someone just sent.
 */
test('an environment that does not exist is called out', async ({page}) => {
  await page.goto('/tables?env=deleted-last-week');

  await expect(page.locator('.env-unknown')).toContainText('deleted-last-week');
});

/**
 * A link that names no environment — a middle-click, a pasted address — opens
 * the page it names, with the stored environment written onto it. It used to
 * land on /tables: the stamp ran before the router's first navigation had
 * finished, so it re-navigated from the router's initial empty URL, `/`, which
 * redirects to /tables (sqlx-dkq).
 */
test('a link without an environment opens its own page, naming the stored one', async ({page}) => {
  await page.goto('/');
  await page.evaluate(() => localStorage.setItem('sql-explorer.environment', 'staging'));

  await page.goto('/coverage');

  await expect(page).toHaveURL(/\/coverage\?env=staging$/);
  await expect(page.locator('.view-title')).toContainText('What was captured');
});

/** Nothing selected still means a clean URL — the default is not invented. */
test('an untouched view names no environment', async ({page}) => {
  await page.goto('/tables');

  await expect(page).not.toHaveURL(/env=/);
});
