import {expect, test} from '@playwright/test';
import {stubApi} from './api';

const BASE = '**/api/sql-explorer';

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
