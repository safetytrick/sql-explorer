import {expect, test} from '@playwright/test';
import {SCOPED, stubApi} from './api';

/** A fifth of a table's bytes in its secondary indexes. */
const node = (schema: string, table: string, queryTimeMs: number, sizeBytes: number | null) =>
  ({schema, table, columnCount: 6, queryCount: 3, warn: false, selfJoin: false, view: false, columns: [],
    executions: 1200, queryTimeMs, sizeBytes,
    dataBytes: sizeBytes === null ? null : sizeBytes * 0.8, indexBytes: sizeBytes === null ? null : sizeBytes * 0.2});
const join = (aTable: string, bTable: string) =>
  ({aSchema: 'app_main', aTable, bSchema: 'app_main', bTable, columns: ['id=id'], queries: 2, executions: 1200});

const GRAPH = {
  nodes: [
    node('app_main', 'account', 420_000, 2_000_000_000),
    node('app_main', 'account_site', 90_000, 400_000_000),
    node('app_main', 'invoice', 1_300_000, 9_000_000_000),
    node('app_main', 'invoice_item', 610_000, 14_000_000_000),
    node('app_main', 'site', 240_000, 700_000_000),
    node('app_main', 'note', 0, null),
    node('app_other', 'tax', 55_000, 30_000_000),
    node('app_other', 'rate', 25_000, 90_000_000)
  ],
  fkEdges: [],
  coQueryEdges: [],
  viewEdges: [],
  joinEdges: [join('account_site', 'account'), join('account_site', 'site'), join('invoice_item', 'invoice'),
    join('invoice', 'account')]
};

/**
 * The treemap is a layout of the workbench graph, and a link like any other
 * view: `var-layout=treemap` opens it, `var-measure=disk` sizes it by bytes
 * on disk rather than query time (sqlx-7o0).
 */
test('the graph opens as a treemap from its link, and its tiles measure query time or disk size', async ({page}, testInfo) => {
  await stubApi(page);
  await page.route(`${SCOPED}/graph/v2/**`, route =>
    route.fulfill({contentType: 'application/json', body: JSON.stringify(GRAPH)}));

  await page.goto('/queries?view=graph&var-layout=treemap&var-legend=1');

  const toolbar = page.locator('.graph-toolbar');
  await expect(toolbar.getByRole('button', {name: 'treemap', exact: true})).toHaveClass(/\bon\b/);
  await expect(toolbar.getByRole('button', {name: 'query time'})).toHaveClass(/\bon\b/);
  await expect(toolbar).toContainText('1 table not drawn: no query time');
  // the grid's arranging tools belong to the grid
  await expect(toolbar.getByRole('button', {name: 'auto-place'})).toHaveCount(0);
  await expect(page.locator('.graph-legend')).toContainText('time of the queries touching it');
  await testInfo.attach('time', {body: await page.locator('app-graph-lens').screenshot(), contentType: 'image/png'});

  await toolbar.getByRole('button', {name: 'disk size'}).click();
  await expect(page).toHaveURL(/var-measure=disk/);
  await expect(page.locator('.graph-legend')).toContainText('data + index bytes on disk');
  await expect(page.locator('.graph-legend')).toContainText('a table\'s data, then its indexes, hatched');
  await expect(toolbar).toContainText('1 table not drawn: no size reported');
  await testInfo.attach('disk', {body: await page.locator('app-graph-lens').screenshot(), contentType: 'image/png'});

  await toolbar.getByRole('button', {name: 'treemap', exact: true}).click();
  await expect(page).not.toHaveURL(/var-layout/);
  await expect(toolbar.getByRole('button', {name: 'disk size'})).toHaveCount(0);
});
