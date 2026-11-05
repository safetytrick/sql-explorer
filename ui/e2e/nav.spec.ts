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
