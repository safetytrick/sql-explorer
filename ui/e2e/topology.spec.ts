import {expect, test} from '@playwright/test';
import {stubApi} from './api';

const BASE = '**/api/sql-explorer/env/*';

const load = (reads: [number, number], writes: [number, number], other: [number, number], perDay: number | null,
              unknown: [number, number] = [0, 0]) => ({
  statements: 4, executions: reads[0] + writes[0] + other[0] + unknown[0],
  seconds: reads[1] + writes[1] + other[1] + unknown[1],
  secondsPerDay: perDay,
  shares: [
    {kind: 'READ', statements: 1, executions: reads[0], seconds: reads[1]},
    {kind: 'WRITE', statements: 1, executions: writes[0], seconds: writes[1]},
    {kind: 'OTHER', statements: 1, executions: other[0], seconds: other[1]},
    {kind: 'UNKNOWN', statements: 1, executions: unknown[0], seconds: unknown[1]}
  ]
});

const WINDOW = {from: '2026-11-01T00:00:00', to: '2026-11-11T00:00:00', days: 10};

const TOPOLOGY = {
  environment: 'local', loaded: true, cached: false, basis: 'Each server\'s own Performance Schema.',
  total: load([1600, 160], [300, 30], [100, 10], 35),
  others: [],
  clusters: [{
    name: 'shard1', load: load([1600, 160], [300, 30], [100, 10], 35),
    servers: [
      {name: 'shard1', roles: ['primary'], schemaSource: true, status: 'READ', load: load([600, 60], [300, 30], [100, 10], 10),
        window: WINDOW, shareOfTime: 0.5, reason: null},
      {name: 'shard1-replica', roles: ['replica', 'sampling'], schemaSource: false, status: 'READ',
        load: load([900, 90], [0, 0], [0, 0], 25, [100, 10]), window: WINDOW, shareOfTime: 0.5, reason: null},
      {name: 'shard1-etl', roles: ['analytics'], schemaSource: false, status: 'NO_SOURCE', load: null, window: null,
        shareOfTime: null, reason: 'config gives this server no statement source'}
    ]
  }]
};

/**
 * Topology (sqlx-a9h): each cluster draws its primary over its replicas and
 * each server shows its read/write split and time. A server opens the
 * queries workbench scoped to its source, so its statements are a search
 * like any other (sqlx-a9h.1).
 */
test('the topology shows each server\'s split and opens its queries scoped to it', async ({page}) => {
  await stubApi(page);
  await page.route(`${BASE}/topology`, route =>
    route.fulfill({contentType: 'application/json', body: JSON.stringify(TOPOLOGY)}));
  const searches: {sources?: string[] | null}[] = [];
  page.on('request', request => {
    if (request.url().endsWith('/queries/search')) {
      searches.push(JSON.parse(request.postData() || '{}'));
    }
  });

  await page.goto('/topology?var-server=shard1');
  // a link from before servers opened the workbench: the selection is dropped
  await expect(page).not.toHaveURL(/var-server/);

  const primary = page.locator('.node', {hasText: 'primary'});
  await expect(primary).toContainText('shard1');
  await expect(primary).toContainText('reads 60%');
  await expect(primary).toContainText('writes 30%');
  await expect(primary).toContainText('50% of env');
  const etl = page.locator('.node', {hasText: 'shard1-etl'});
  await expect(etl).toContainText('not captured');
  // nothing was captured from it, so there is nothing to open
  await expect(etl).not.toHaveAttribute('href');
  // a statement whose operation could not be read is keyed only where there is one
  await expect(page.locator('.node', {hasText: 'shard1-replica'})).toContainText('unknown 10%');
  await expect(primary).not.toContainText('unknown');
  // the replica sits below the primary
  await expect(page.locator('.tier.below .node')).toHaveCount(2);

  await page.getByRole('button', {name: 'executions'}).click();
  await expect(page).toHaveURL(/var-by=executions/);
  await expect(primary).toContainText('reads 60%');

  await primary.click();
  await expect(page).toHaveURL(/\/queries\?sources=shard1$/);
  await expect.poll(() => searches.some(body => body.sources?.join() === 'shard1')).toBe(true);
});
