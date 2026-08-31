import {expect, test} from '@playwright/test';
import {stubApi} from './api';

/**
 * The scope — `schemaTagsExclude` and friends — says which part of the corpus
 * you are working in, travels across navigations, and must be honoured by
 * every page that carries it (sqlx-uxo).
 *
 * <p>Two real defects this would have caught: `/unqueried` carried the scope
 * in its URL and ignored it (sqlx-g7z), and the graph's schema select
 * silently deleted a tag scope (sqlx-p7u). Both reached a person first.
 */
test.beforeEach(async ({page}) => {
  await stubApi(page);
});

test('an excluded schema is subtracted from /unqueried', async ({page}) => {
  await page.goto('/unqueried');
  // mysql, app and ops all have unqueried tables
  await expect(page.locator('.card-head-title')).toHaveCount(3);

  await page.goto('/unqueried?schemaTagsExclude=system');

  // mysql carries `system`, so it goes; the other two stay
  await expect(page.locator('.card-head-title')).toHaveCount(2);
  await expect(page.locator('.card-head-title')).not.toContainText(['mysql']);
});

/**
 * A page that carries a scope in its URL and ignores it is worse than one
 * that never claimed it: the link asserts a filter the view does not apply.
 */
test('the scope survives a reload on /unqueried', async ({page}) => {
  await page.goto('/unqueried?schemaTagsExclude=system');
  await expect(page.locator('.card-head-title')).toHaveCount(2);

  await page.reload();

  await expect(page.locator('.card-head-title')).toHaveCount(2);
});

/** Looking past the scope must not silently change it. */
test('showing the excluded schemas leaves the scope in the URL', async ({page}) => {
  await page.goto('/unqueried?schemaTagsExclude=system');
  await expect(page.locator('.card-head-title')).toHaveCount(2);

  // by title, not role-name: the button's accessible name is just "show"
  await page.getByTitle(/show the schemas the scope leaves out/).click();

  await expect(page.locator('.card-head-title')).toHaveCount(3);
  await expect(page).toHaveURL(/schemaTagsExclude=system/);
  await expect(page).toHaveURL(/var-excluded=1/);
});

/**
 * The scope is cross-cutting, so it follows a navigation. A page-local filter
 * must not — that distinction is the whole reason the two vocabularies are
 * kept apart (sqlx-04q).
 */
test('the scope follows a navigation between pages', async ({page}) => {
  await page.goto('/unqueried?schemaTagsExclude=system');
  await expect(page.locator('.card-head-title')).toHaveCount(2);

  await page.getByRole('link', {name: 'Queries', exact: true}).click();

  await expect(page).toHaveURL(/schemaTagsExclude=system/);
});
