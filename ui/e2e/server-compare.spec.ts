import {expect, test} from '@playwright/test';
import {SCOPED, stubApi} from './api';

const cell = (executionsPerDay: number, rowsExaminedPerExecution: number, msPerExecution: number) => ({
  executions: executionsPerDay * 10, seconds: executionsPerDay * msPerExecution / 100,
  secondsPerDay: executionsPerDay * msPerExecution / 1000, executionsPerDay, rowsExaminedPerExecution,
  msPerExecution, rowsAffected: 0
});

const SERVERS = [
  {name: 'shard1', roles: ['primary'], read: true, windowDays: 10},
  {name: 'shard1-replica-a', roles: ['replica'], read: true, windowDays: 10},
  {name: 'shard1-replica-b', roles: ['replica'], read: true, windowDays: 4}
];

const report = (flags: string[] | null, all: boolean) => {
  const rows = [
    {key: 'v3:plan', text: 'select * from account where site_id = ?', kind: 'READ', flags: ['PLAN_DIFFERS'],
      difference: 4.9, secondsPerDay: 5.1, queries: 1, cells: [null, cell(10, 10, 10), cell(10, 5000, 500)]},
    {key: 'v3:skew', text: 'select name from site where id = ?', kind: 'READ', flags: ['SKEWED'],
      difference: 2, secondsPerDay: 5, queries: 1, cells: [null, cell(450, 1, 10), cell(50, 1, 10)]},
    {key: 'v3:primary', text: 'select id from account_site', kind: 'READ', flags: ['ON_PRIMARY'],
      difference: 1, secondsPerDay: 3, queries: 1, cells: [cell(100, 1, 10), cell(100, 1, 10), cell(100, 1, 10)]},
    {key: 'v3:same', text: 'select 1', kind: 'READ', flags: [],
      difference: 0, secondsPerDay: 0.3, queries: 1, cells: [null, cell(100, 1, 1), cell(100, 1, 1)]}
  ].filter(row => flags?.length ? row.flags.some(flag => flags.includes(flag)) : all || row.flags.length);
  return {
    environment: 'local', clusters: ['shard1', 'shard2'], cluster: 'shard1', servers: SERVERS, by: 'QUERY',
    matched: 6, compared: 4, flagCounts: {PLAN_DIFFERS: 1, SKEWED: 1, ON_PRIMARY: 1}, unflagged: 1,
    total: rows.length, rows, basis: 'Each server\'s own figures, per day of its own window.'
  };
};

/**
 * The server comparison (sqlx-a9h.2) is a view of the workbench's search, like
 * the graph: the search decides which queries are compared, and the rows that
 * run alike everywhere are hidden so the differences lead.
 */
test('the workbench compares its search across a cluster\'s servers, differences first', async ({page}, testInfo) => {
  await stubApi(page);
  const asked: {flags: string | null; all: string | null; search: {queryRegex?: string}}[] = [];
  await page.route(`${SCOPED}/topology/compare**`, route => {
    const url = new URL(route.request().url());
    const flags = url.searchParams.get('flags');
    const all = url.searchParams.get('all');
    asked.push({flags, all, search: JSON.parse(route.request().postData() || '{}')});
    return route.fulfill({contentType: 'application/json',
      body: JSON.stringify(report(flags ? flags.split(',') : null, all === 'true'))});
  });

  await page.goto('/queries?q=select&view=servers');
  const pane = page.locator('app-server-compare');
  await expect(pane.locator('tbody tr')).toHaveCount(3);
  await expect(pane).toContainText('1 with no flag hidden');
  expect(asked[0].search.queryRegex).toBe('select');
  // the plan difference leads, and the server examining 5,000 rows is the cell marked
  const plan = pane.locator('tbody tr').first();
  await expect(plan).toContainText('plan differs');
  await expect(plan.locator('td.cell').nth(2)).toHaveClass(/\bhot\b/);
  await testInfo.attach('compare', {body: await pane.screenshot(), contentType: 'image/png'});

  await pane.locator('.cmp-flags').getByRole('button', {name: /uneven/}).click();
  await expect(page).toHaveURL(/var-flags=skewed/);
  await expect(pane.locator('tbody tr')).toHaveCount(1);
  await expect(pane.locator('tbody tr')).toContainText('select name from site');

  await pane.locator('.cmp-flags').getByRole('button', {name: /uneven/}).click();
  await pane.getByText('show all', {exact: true}).click();
  await expect(page).toHaveURL(/var-all=1/);
  await expect(pane.locator('tbody tr')).toHaveCount(4);

  // a server's header opens the same search, read from that server alone
  await pane.locator('th.col-server a', {hasText: 'shard1-replica-b'}).click();
  await expect(page).toHaveURL(/sources=shard1-replica-b/);
  await expect(page).toHaveURL(/q=select/);
});
