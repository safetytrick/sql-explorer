import {expect, test} from '@playwright/test';
import {stubApi} from './api';

/**
 * The schema tree and the filters fold away so the results get the page
 * (sqlx-6xw), and a folded view is a link like any other.
 */
test.beforeEach(async ({page}) => {
  await stubApi(page);
});

test('the focus button folds both panels into the link, and a link opens folded', async ({page}) => {
  await page.goto('/queries?q=orders');
  await expect(page.locator('app-schema-tree')).toBeVisible();
  await expect(page.locator('.filter-sidebar')).toBeVisible();

  await page.locator('.focus-btn').click();
  await expect(page).toHaveURL(/var-hide=tree%2Cfilters|var-hide=tree,filters/);
  await expect(page.locator('app-schema-tree')).toBeHidden();
  await expect(page.locator('.filter-sidebar')).toHaveCount(0);
  // the folded filters still say they are filtering
  await expect(page.locator('.rail-tab', {hasText: 'Filters'}).locator('.rail-badge')).toHaveText('1');

  await page.reload();
  await expect(page.locator('app-schema-tree')).toBeHidden();
  await page.locator('.rail-tab', {hasText: 'Schemas'}).click();
  await expect(page.locator('app-schema-tree')).toBeVisible();
  await expect(page).toHaveURL(/var-hide=filters(?!%2C|,)/);
});

test('the tree folds itself from its own header', async ({page}) => {
  await page.goto('/queries');
  await page.locator('app-schema-tree .fold').click();
  await expect(page.locator('app-schema-tree')).toBeHidden();
  await expect(page).toHaveURL(/var-hide=tree/);
});
