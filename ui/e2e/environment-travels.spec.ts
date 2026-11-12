import {expect, test} from '@playwright/test';
import {stubApi, chooseEnvironment} from './api';

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

  await page.goto('/staging/tables');

  await expect(page.locator('.env-current')).toHaveAttribute('data-env', 'staging');
  
});

/** Links shared before the environment moved into the path still open on it, in the new form. */
test('an old ?env= link opens on its environment and is rewritten to the path', async ({page}) => {
  await page.goto('/tables?env=staging');

  await expect(page.locator('.env-current')).toHaveAttribute('data-env', 'staging');
  await expect(page).toHaveURL(/\/staging\/tables$/);
});

/**
 * The environment is written ALWAYS, including when it is the default: the
 * bare URL is precisely the ambiguous case.
 */
test('picking an environment writes it to the address', async ({page}) => {
  await page.goto('/tables');
  await chooseEnvironment(page, 'staging');

  await expect(page).toHaveURL(/\/staging\/tables$/);
});

test('the environment survives a navigation between pages', async ({page}) => {
  await page.goto('/staging/tables');
  await page.getByRole('link', {name: 'Queries', exact: true}).click();

  await expect(page).toHaveURL(/\/staging\/queries/);
});

/**
 * An unknown name is REPORTED, not substituted silently: the backend answers
 * it with a 404, so the app reads the default instead and says so.
 */
test('an environment that does not exist is called out', async ({page}) => {
  await page.goto('/deleted-last-week/tables');

  await expect(page.locator('.env-unknown')).toContainText('deleted-last-week');
  await expect(page).toHaveURL(/\/deleted-last-week\/tables$/);
});

/**
 * A link that names no environment — a middle-click, a pasted address — opens
 * the page it names, with the stored environment written onto it (sqlx-dkq).
 */
test('a link without an environment opens its own page, naming the stored one', async ({page}) => {
  await page.goto('/');
  await page.evaluate(() => localStorage.setItem('sql-explorer.environment', 'staging'));

  await page.goto('/captured');

  await expect(page).toHaveURL(/\/staging\/captured$/);
  await expect(page.locator('.view-title')).toContainText('Captured');
});

/** With nothing selected, the backend's default is named once it is known. */
test('an untouched view names the default environment', async ({page}) => {
  await page.goto('/tables');

  await expect(page).toHaveURL(/\/regression\/tables$/);
});
