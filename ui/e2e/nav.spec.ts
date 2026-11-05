import {expect, test} from '@playwright/test';
import {stubApi} from './api';

test.beforeEach(async ({page}) => {
  await stubApi(page);
});

/**
 * Routed nav entries have an href and the global `a` rule underlined them,
 * while Explain and Admin (click handlers, no href) stayed plain — so the nav
 * read as two different kinds of thing (sqlx-m6r.2).
 */
test('no nav entry is underlined, routed or not', async ({page}) => {
  await page.goto('/schemas');
  const entries = page.locator('nav.navbar a');
  await expect(entries.first()).toBeVisible();
  const decorations = await entries.evaluateAll(links =>
    links.map(link => getComputedStyle(link).textDecorationLine));
  expect(decorations.length).toBeGreaterThan(5);
  expect(decorations.filter(line => line !== 'none')).toEqual([]);
});

/** The schema tree is the workbench's scope picker; elsewhere it is not drawn or loaded (sqlx-pyz.1). */
test('the schema tree appears on the workbench only', async ({page}) => {
  let treeRequests = 0;
  page.on('request', request => { if (request.url().endsWith('/schema-tree')) { treeRequests++; } });

  await page.goto('/tables');
  await expect(page.locator('.data-table tbody tr').first()).toBeVisible();
  await expect(page.locator('app-schema-tree')).toHaveCount(0);
  expect(treeRequests).toBe(0);

  await page.locator('nav.navbar a', {hasText: 'Queries'}).click();
  await expect(page.locator('app-schema-tree')).toHaveCount(1);
});

/** The environment reads as a status, and its chooser says the same about each choice (sqlx-m6r.6). */
test('the environment shows what is loaded, and the chooser lists each with its status', async ({page}) => {
  await page.goto('/schemas');
  const current = page.locator('.env-current');
  await expect(current).toHaveAttribute('data-env', 'regression');
  await expect(current.locator('.env-dot.loaded')).toHaveCount(1);

  await current.click();
  await expect(page.locator('.env-option')).toHaveCount(2);
  await expect(page.locator('.env-option.on')).toContainText('regression');
  await page.keyboard.press('Escape');
  await expect(page.locator('.env-menu')).toHaveCount(0);
});

/** Primary pages first, working pages next, operator pages under Admin (sqlx-m6r.3). */
test('the nav leads with the primary pages and keeps operator pages under Admin', async ({page}) => {
  await page.goto('/schemas');
  await expect(page.locator('a.navbar-brand')).toHaveText(['Schemas', 'Tables', 'Queries', 'Graph']);
  await expect(page.locator('nav.navbar a', {hasText: 'Captured'})).toHaveCount(0);

  await page.locator('.dropdown-toggle', {hasText: 'Admin'}).click();
  await expect(page.locator('.dropdown-item')).toHaveText(['Captured', 'Schema drift', 'Sources', 'Query utilities']);
  await page.locator('.dropdown-item', {hasText: 'Captured'}).click();
  await expect(page).toHaveURL(/\/captured/);
});
