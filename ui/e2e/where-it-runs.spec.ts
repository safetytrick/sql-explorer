import {expect, test} from '@playwright/test';
import {ORDERS, SCOPED, stubApi} from './api';

const SOURCES = [
  {source: 'shard1-replica', cluster: 'shard1', roles: ['replica'], capturedAt: '2026-11-11T12:00:00Z',
    executions: 300, seconds: 30, averageMillis: 100, rowsScanned: 900, rowsSent: 300, rowsAffected: 0,
    firstSeen: '2026-11-01T00:00:00', lastSeen: '2026-11-10T00:00:00', shareOfTime: 0.75},
  {source: 'shard1', cluster: 'shard1', roles: ['primary'], capturedAt: '2026-11-11T12:00:00Z',
    executions: 100, seconds: 10, averageMillis: 100, rowsScanned: 300, rowsSent: 100, rowsAffected: 0,
    firstSeen: '2026-11-01T00:00:00', lastSeen: '2026-11-10T00:00:00', shareOfTime: 0.25}
];

/**
 * The query page says where the statement runs: each server's own figures,
 * and a link to everything that ran there (sqlx-rw2.9).
 */
test('the query page lists the servers a statement runs on, each opening its queries', async ({page}) => {
  await stubApi(page);
  const json = (body: unknown) => ({contentType: 'application/json', body: JSON.stringify(body)});
  await page.route(`${SCOPED}/analyzed-sql/${ORDERS.normalizedDigest}`, route => route.fulfill(json(ORDERS)));
  await page.route(`${SCOPED}/analyzed-sql/${ORDERS.normalizedDigest}/sources`, route => route.fulfill(json(SOURCES)));

  await page.goto(`/analyzed-sql/${ORDERS.normalizedDigest}`);

  const rows = page.locator('.where-it-runs tbody tr');
  await expect(rows).toHaveCount(2);
  await expect(rows.first()).toContainText('shard1-replica');
  await expect(rows.first()).toContainText('75%');
  await expect(rows.nth(1)).toContainText('primary');

  await rows.first().getByRole('link', {name: 'shard1-replica'}).click();
  await expect(page).toHaveURL(/\/queries\?sources=shard1-replica$/);
});
