import {expect, test} from '@playwright/test';
import {stubApi} from './api';

const BASE = '**/api/sql-explorer/env/*';

/**
 * The DDL pre-flight (sqlx-n5j.11): a shared link carries the statement, and
 * opening it runs the check with that statement.
 */
test('a pre-flight link runs its statement and shows the verdict', async ({page}) => {
  await stubApi(page);
  let posted: {sql?: string; longStatementSeconds?: number} = {};
  await page.route(`${BASE}/ddl/preflight`, async route => {
    posted = JSON.parse(route.request().postData() || '{}');
    await route.fulfill({contentType: 'application/json', body: JSON.stringify({
      statement: posted.sql, error: null, schema: 'app', table: 'orders',
      clauses: [{text: 'ADD COLUMN note VARCHAR(40)', kind: 'ADD_COLUMN', algorithm: 'INSTANT', rebuildsTable: false,
        concurrentDml: true, reason: 'ADD COLUMN is instant.'}],
      algorithm: 'INSTANT', rebuildsTable: false, concurrentDml: true, requestedAlgorithm: null, requestedLock: null,
      tableFacts: {known: true, rowsEstimate: 1200, sizeBytes: 65536, rowFormat: 'Dynamic',
        declaredCollation: 'utf8mb4_0900_ai_ci', servers: 1, childOfForeignKey: false, referencedBy: []},
      workload: {writesPerDay: 40, rowsChangedPerDay: 40, longThresholdMs: 5000, longStatements: 0,
        longExecutionsPerDay: 0, maxTimeMs: 12},
      checks: [{severity: 'INFO', title: 'No ALGORITHM= stated', detail: 'Add ALGORITHM=INSTANT.'}],
      recommendations: ['Run it natively, with ALGORITHM=INSTANT.'], unknowns: []
    })});
  });

  await page.goto('/ddl-preflight?var-ddl=' + encodeURIComponent('ALTER TABLE app.orders ADD COLUMN note VARCHAR(40)')
    + '&var-long=5');

  await expect(page.locator('.verdict-value')).toHaveText('INSTANT · metadata only');
  await expect(page.locator('.checks')).toContainText('No ALGORITHM= stated');
  expect(posted.sql).toBe('ALTER TABLE app.orders ADD COLUMN note VARCHAR(40)');
  expect(posted.longStatementSeconds).toBe(5);
});

/**
 * A table's change page: the builder writes a statement from the table's own
 * columns, and checking it puts the statement in the link.
 */
test('the change page builds an index from clicked columns and checks it', async ({page}) => {
  await stubApi(page);
  await page.route(`${BASE}/metadata/table/app/orders`, route => route.fulfill({contentType: 'application/json',
    body: JSON.stringify({
      schema: 'app', name: 'orders', type: 'TABLE', remarks: '', foreignKeys: [], underlyingTables: null,
      viewColumns: null,
      indices: [{name: 'PRIMARY', unique: true, columns: ['id']}],
      columns: [
        {columnName: 'id', typeName: 'BIGINT', columnSize: 20, decimalDigits: 0, ordinal: 1, isNullable: 'NO',
          isAutoIncrement: 'YES', characterSet: null, collation: null},
        {columnName: 'site_id', typeName: 'BIGINT', columnSize: 20, decimalDigits: 0, ordinal: 2, isNullable: 'NO',
          isAutoIncrement: 'NO', characterSet: null, collation: null},
        {columnName: 'status', typeName: 'VARCHAR', columnSize: 16, decimalDigits: 0, ordinal: 3, isNullable: 'NO',
          isAutoIncrement: 'NO', characterSet: 'utf8mb4', collation: 'utf8mb4_0900_ai_ci'}]
    })}));
  let posted = '';
  await page.route(`${BASE}/ddl/preflight`, async route => {
    posted = JSON.parse(route.request().postData() || '{}').sql;
    await route.fulfill({contentType: 'application/json', body: JSON.stringify({
      statement: posted, error: null, schema: 'app', table: 'orders',
      clauses: [{text: 'ADD INDEX', kind: 'ADD_INDEX', algorithm: 'INPLACE', rebuildsTable: false,
        concurrentDml: true, reason: 'A secondary index is built in place.'}],
      algorithm: 'INPLACE', rebuildsTable: false, concurrentDml: true, requestedAlgorithm: null, requestedLock: null,
      tableFacts: null, workload: null, checks: [], recommendations: ['Run it natively.'], unknowns: [],
      choices: [
        {algorithm: 'DEFAULT', outcome: 'CHOSEN', requested: false, implication: 'MySQL picks INPLACE here.'},
        {algorithm: 'INSTANT', outcome: 'REFUSED', requested: false, implication: 'Refused: the change needs INPLACE.'},
        {algorithm: 'INPLACE', outcome: 'NEEDED', requested: true, implication: 'Works in place; writes continue.'},
        {algorithm: 'COPY', outcome: 'SLOWER', requested: false, implication: 'Accepted, but forces a full copy.'}]
    })});
  });

  await page.goto('/table/app/orders/ddl?var-op=add-index');
  await page.locator('tr.pick', {hasText: 'site_id'}).click();
  await page.locator('tr.pick', {hasText: 'status'}).click();
  await page.locator('label.algorithm select').selectOption('INPLACE');
  await expect(page).toHaveURL(/var-alg=INPLACE/);
  await page.getByRole('button', {name: 'Check this ↓'}).click();

  await expect(page.locator('.verdict-value')).toHaveText('INPLACE · writes continue');
  expect(posted).toBe('CREATE INDEX `idx_orders_site_id_status`\n  ON `app`.`orders` (`site_id`, `status`)\n  ALGORITHM=INPLACE');
  await expect(page).toHaveURL(/var-ddl=CREATE/);
  await expect(page.locator('table.choices tr')).toHaveCount(4);
  await expect(page.locator('table.choices tr.asked')).toContainText('ALGORITHM=INPLACE');
  await expect(page.locator('table.choices tr[data-outcome="REFUSED"]')).toContainText('refused');
});

/**
 * Dropping a foreign key (sqlx-64v): the table's own constraints are listed to
 * click, the statement drops only the constraint, and the index it leaves is
 * offered as the next change.
 */
test('the change page drops a foreign key constraint and keeps its column', async ({page}) => {
  await stubApi(page);
  await page.route(`${BASE}/metadata/table/app/orders`, route => route.fulfill({contentType: 'application/json',
    body: JSON.stringify({
      schema: 'app', name: 'orders', type: 'TABLE', remarks: '', foreignKeys: [], underlyingTables: null,
      viewColumns: null,
      indices: [{name: 'PRIMARY', unique: true, columns: ['id']}, {name: 'fk_orders_site', unique: false, columns: ['site_id']}],
      columns: [
        {columnName: 'id', typeName: 'BIGINT', columnSize: 20, decimalDigits: 0, ordinal: 1, isNullable: 'NO',
          isAutoIncrement: 'YES', characterSet: null, collation: null},
        {columnName: 'site_id', typeName: 'BIGINT', columnSize: 20, decimalDigits: 0, ordinal: 2, isNullable: 'NO',
          isAutoIncrement: 'NO', characterSet: null, collation: null}]
    })}));
  await page.route(`${BASE}/metadata/table/app/orders/foreign-keys`, route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify([
      {direction: 'out', constraintName: 'fk_orders_site', onUpdate: null, onDelete: null, otherSchema: 'app',
        otherTable: 'site', columns: [{fromColumn: 'site_id', fromType: 'BIGINT', toColumn: 'id', toType: 'BIGINT',
          typeMismatch: false}]},
      {direction: 'in', constraintName: 'fk_line_orders', onUpdate: null, onDelete: null, otherSchema: 'app',
        otherTable: 'order_line', columns: [{fromColumn: 'order_id', fromType: 'BIGINT', toColumn: 'id',
          toType: 'BIGINT', typeMismatch: false}]}])}));

  await page.goto('/table/app/orders/ddl');
  // only the constraint this table owns can be dropped from here
  await expect(page.locator('.fk-row.pick', {hasText: 'fk_line_orders'})).toHaveCount(0);
  await expect(page.locator('.fk-row.pick', {hasText: 'fk_orders_site'})).toContainText('(site_id) → site(id)');
  await expect(page.locator('.fk-row', {hasText: 'fk_line_orders'})).toContainText('order_line(order_id) → (id)');
  await page.locator('.fk-row.pick', {hasText: 'fk_orders_site'}).click();

  await expect(page).toHaveURL(/var-op=drop-foreign-key/);
  await expect(page.locator('.preview code')).toHaveText('ALTER TABLE `app`.`orders`\n  DROP FOREIGN KEY `fk_orders_site`');
  await expect(page.locator('.fields .hint')).toContainText('site_id keeps its data');

  let posted = '';
  await page.route(`${BASE}/ddl/preflight`, async route => {
    posted = JSON.parse(route.request().postData() || '{}').sql;
    await route.fulfill({contentType: 'application/json', body: JSON.stringify({
      statement: posted, error: null, schema: 'app', table: 'orders',
      clauses: [{text: 'DROP FOREIGN KEY `fk_orders_site`', kind: 'DROP_FOREIGN_KEY', algorithm: 'INPLACE',
        rebuildsTable: false, concurrentDml: true, reason: 'Metadata only: nothing is rewritten.'}],
      algorithm: 'INPLACE', rebuildsTable: false, concurrentDml: true, requestedAlgorithm: null, requestedLock: null,
      tableFacts: null, workload: null, checks: [], recommendations: ['Run it natively.'], unknowns: [],
      lockedTables: [{schema: 'app', table: 'site', role: 'PARENT', reach: 'EVERY_QUERY', waits: 'every query',
        constraints: ['fk_orders_site'], reason: 'Dropping its foreign key takes an exclusive lock on it.', workload: {writesPerDay: 12, rowsChangedPerDay: 12,
          longThresholdMs: 1000, longStatements: 2, longExecutionsPerDay: 3, maxTimeMs: 95000}},
        {schema: 'app', table: 'region', role: 'PARENT', reach: 'WRITES', waits: 'UPDATE and DELETE',
          constraints: ['fk_orders_region'], reason: 'This table references it.', workload: null}]
    })});
  });
  await page.getByRole('button', {name: 'Check this ↓'}).click();
  expect(posted).toBe('ALTER TABLE `app`.`orders`\n  DROP FOREIGN KEY `fk_orders_site`');
  const locked = page.locator('.locked-tables');
  await expect(locked).toContainText('app.site');
  await expect(locked).toContainText('1.6 min');
  await expect(locked.locator('tr', {hasText: 'app.region'})).toContainText('UPDATE and DELETE');

  await page.getByRole('button', {name: 'drop it next'}).click();
  await expect(page).toHaveURL(/var-op=drop-index/);
  await expect(page.locator('.preview code')).toHaveText('DROP INDEX `fk_orders_site` ON `app`.`orders`');
});

/**
 * "Open transactions now" (sqlx-9gk): opening the page, even from a link that
 * carries a server selection, reads nothing from any server. Only the button
 * does, and only the servers chosen.
 */
test('open transactions are read only on request, from the servers chosen', async ({page}) => {
  await stubApi(page);
  await page.route('**/api/sql-explorer/environments', route => route.fulfill({contentType: 'application/json',
    body: JSON.stringify({defaultEnvironment: 'regression', environments: [
      {name: 'regression', sources: {loaded: true, sourceCount: 1, loadedOn: '2026-08-30T12:00:00Z',
        importStats: null, resident: true},
        servers: [
          {name: 'db-primary', schemaLoaded: true, roles: ['primary'], cluster: 'main'},
          {name: 'db-replica', schemaLoaded: true, roles: ['replica'], cluster: 'main'}]}]})}));
  await page.route(`${BASE}/ddl/preflight`, route => route.fulfill({contentType: 'application/json',
    body: JSON.stringify({
      statement: 'ALTER TABLE app.orders ADD COLUMN note VARCHAR(40)', error: null, schema: 'app', table: 'orders',
      clauses: [], algorithm: 'INSTANT', rebuildsTable: false, concurrentDml: true, requestedAlgorithm: null,
      requestedLock: null, tableFacts: null, workload: null, checks: [], recommendations: [], unknowns: [],
      lockedTables: [
        {schema: 'app', table: 'order_line', role: 'CHILD', reach: 'EVERY_QUERY', waits: 'every query',
          constraints: ['fk_order_line_order'], reason: '', workload: null},
        {schema: 'app', table: 'account', role: 'PARENT', reach: 'WRITES', waits: 'UPDATE and DELETE',
          constraints: ['fk_orders_account'], reason: '', workload: null}]
    })}));
  const requests: {tables: {schema: string; table: string}[]; servers: string[]}[] = [];
  await page.route(`${BASE}/ddl/lock-holders`, async route => {
    requests.push(JSON.parse(route.request().postData() || '{}'));
    await route.fulfill({contentType: 'application/json', body: JSON.stringify({
      environment: 'regression', readAt: '2026-11-20T10:00:00Z', tookMillis: 12,
      tables: requests[0].tables,
      servers: [{server: 'db-replica', roles: ['replica'], error: null, note: null, holders: [
        {schema: 'app', table: 'order_line', lockType: 'SHARED_READ', duration: 'TRANSACTION', status: 'GRANTED',
          processlistId: 7, user: 'reader', host: '10.0.0.1', command: 'Sleep', state: null,
          secondsInState: 300, transactionSeconds: 725}]}]
    })});
  });

  await page.goto('/ddl-preflight?var-servers=db-replica&var-ddl='
    + encodeURIComponent('ALTER TABLE app.orders ADD COLUMN note VARCHAR(40)'));

  const card = page.locator('.lock-holders');
  await expect(card).toContainText('app.order_line');
  await expect(card.locator('.chip')).toHaveText(['app.orders', 'app.order_line']);
  await expect(card.getByRole('checkbox', {name: /db-replica/})).toBeChecked();
  await expect(card.getByRole('checkbox', {name: /db-primary/})).not.toBeChecked();
  await page.waitForTimeout(300);
  expect(requests).toHaveLength(0);

  await card.getByRole('button', {name: 'Check for open transactions'}).click();

  await expect(card.locator('.holders-verdict')).toHaveText('oldest open transaction: 12m 05s, on app.order_line');
  expect(requests).toHaveLength(1);
  expect(requests[0].servers).toEqual(['db-replica']);
  expect(requests[0].tables).toEqual([{schema: 'app', table: 'orders'}, {schema: 'app', table: 'order_line'}]);

  await card.getByRole('checkbox', {name: /db-primary/}).check();
  await expect(page).toHaveURL(/var-servers=db-primary%2Cdb-replica|var-servers=db-primary,db-replica/);
  expect(requests).toHaveLength(1);
});
