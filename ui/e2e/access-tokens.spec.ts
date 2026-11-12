import {expect, test} from '@playwright/test';
import {stubApi, stubTokens} from './api';

test.beforeEach(async ({page}) => {
  await stubApi(page);
  await stubTokens(page);
});

/** Personal access tokens (sqlx-e13.5): reached from the signed-in user in the nav. */
test('the nav links to the access tokens page, which lists them', async ({page}) => {
  await page.goto('/regression/schemas');
  await page.locator('app-signed-in-user a.tokens').click();
  await expect(page).toHaveURL(/\/regression\/tokens$/);
  await expect(page.locator('.view-title')).toHaveText('Access tokens');
  await expect(page.locator('tr[data-token="1"]')).toContainText('nightly report');
  await expect(page.locator('tr[data-token="1"]')).toContainText('sqlx_Old11111…');
});

test('making a token shows its secret once, and never puts it in the address', async ({page}) => {
  await page.goto('/regression/tokens');
  await page.locator('.new-token').click();
  // the open form is part of the view, so it can be linked to (the MCP page does)
  await expect(page).toHaveURL(/var-new=true/);

  await page.locator('input.token-name').fill('laptop MCP');
  await page.locator('.scope[data-scope="regression"] input[data-grant="viewer:regression"]').check();
  await page.locator('select.lifetime').selectOption({label: '30 days'});
  const made = page.waitForRequest(request => request.url().endsWith('/api/sql-explorer/tokens') && request.method() === 'POST');
  await page.locator('button.create').click();
  expect((await made).postDataJSON()).toEqual({name: 'laptop MCP', ceiling: ['viewer:regression'], expiresInDays: 30});

  await expect(page.locator('.secret')).toHaveText('sqlx_New22222-the-whole-secret-shown-once');
  await expect(page).not.toHaveURL(/var-new/);
  expect(page.url()).not.toContain('sqlx_');
  await expect(page.locator('tbody tr').first()).toContainText('laptop MCP');

  // gone once dismissed: it is held nowhere else
  await page.locator('.secret-card button', {hasText: 'Done'}).click();
  await expect(page.locator('.secret')).toHaveCount(0);
});

test('revoking asks twice, then marks it revoked', async ({page}) => {
  await page.goto('/regression/tokens');
  const row = page.locator('tr[data-token="1"]');
  await row.locator('button.revoke').click();
  await expect(row.locator('button.revoke')).toHaveText('Really revoke?');
  await row.locator('button.revoke').click();
  await expect(row.locator('.status')).toContainText('revoked');
  await expect(row.locator('button.revoke')).toHaveCount(0);
});

test('an administrator\'s view of everyone\'s tokens is a link', async ({page}) => {
  await page.goto('/regression/tokens?var-all=true');
  await expect(page.locator('.all-toggle input')).toBeChecked();
  await expect(page.locator('.card-head-title', {hasText: "Everyone's tokens"})).toBeVisible();
  await page.locator('.all-toggle input').uncheck();
  await expect(page).not.toHaveURL(/var-all/);
});
