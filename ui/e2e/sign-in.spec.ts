import {expect, test} from '@playwright/test';
import {stubApi} from './api';

test.beforeEach(async ({page}) => {
  await stubApi(page);
});

/** Sign-in (sqlx-e13.1): who is signed in shows in the nav, with a way out. */
test('the nav shows who is signed in', async ({page}) => {
  await page.goto('/tables');
  await expect(page.locator('app-signed-in-user .who')).toHaveText('Viewer');
  await expect(page.locator('app-signed-in-user .sign-out')).toBeVisible();
});

/** With sign-in off, a quiet note instead of a user. */
test('with sign-in off the nav says it is not configured', async ({page}) => {
  await page.route('**/api/sql-explorer/user', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({mode: 'none', user: {id: 1, email: 'admin'}, issuer: null, subject: null, groups: []})
  }));
  await page.goto('/tables');
  await expect(page.locator('app-signed-in-user .auth-off')).toHaveText('sign-in not configured');
  await expect(page.locator('app-signed-in-user .sign-out')).toHaveCount(0);
});

/**
 * The API answers 401 without a session, never a redirect an XHR could not
 * follow; the page then goes to the login entry point itself, saying where to
 * come back to.
 */
test('a 401 from the API sends the browser to sign in, and back here after', async ({page}) => {
  await page.route('**/api/sql-explorer/user', route => route.fulfill({
    status: 401, contentType: 'application/json',
    body: JSON.stringify({status: 401, error: 'Unauthorized', login: '/api/sql-explorer/login'})
  }));
  const login = page.waitForRequest(request => request.url().includes('/api/sql-explorer/login'));
  await page.route('**/api/sql-explorer/login?**', route => route.fulfill({status: 200, contentType: 'text/plain', body: 'login'}));

  await page.goto('/tables?var-type=views');

  const url = new URL((await login).url());
  expect(url.pathname).toBe('/api/sql-explorer/login');
  // the environment leads the path by then (sqlx-e13.7), so sign-in comes back to the same corpus
  expect(url.searchParams.get('return')).toBe('/regression/tables?var-type=views');
});
