import {expect, test} from '@playwright/test';
import {ORDERS, SCOPED, stubApi} from './api';

const index = (schema: string, name: string, columns: string[], unique = false) => ({
  schema, unique, identifier: name, name, indexType: 'BTREE', direction: 'ASC', cardinality: 100, pages: 1,
  filterCondition: '', columns
});

const column = (schema: string, table: string, columnName: string) => ({
  schema, table, columnName, type: 'VARCHAR', typeName: 'varchar', columnSize: 64, decimalDigits: 0, radix: 10,
  remarks: '', columnDefault: ''
});

const structure = (name: string, columns: string[], indices: ReturnType<typeof index>[]) => ({
  schema: 'app', name, type: 'TABLE', remarks: '', foreignKeys: [], indices,
  columns: columns.map(c => column('app', name, c)), underlyingTables: null, viewColumns: null, stats: null
});

const SELECTED = Array.from({length: 120}, (_, i) => `col_${i}`);

/** A statement long enough to scroll, constraining both tables only at its foot. */
const LONG = {
  ...ORDERS,
  query: 'select\n' + SELECTED.map(c => `  o.${c},`).join('\n') +
    '\n  u.email\nfrom app.orders o\njoin app.users u on u.id = o.user_id\nwhere o.status = ?',
  tables: ['app.orders', 'app.users'],
  criteriaColumns: ['app.orders.status'],
  joinColumns: [['app.users.id', 'app.orders.user_id']]
};

const ORDERS_TABLE = structure('orders', ['id', 'status', 'user_id', ...SELECTED], [
  index('app', 'PRIMARY', ['id'], true),
  index('app', 'idx_status', ['status']),
  index('app', 'idx_user', ['user_id']),
  // enough unrelated indexes that the whole card is taller than the screen
  ...Array.from({length: 30}, (_, i) => index('app', `idx_other_${i}`, [`col_${i}`]))
]);
const USERS_TABLE = structure('users', ['id', 'email'], [
  index('app', 'PRIMARY', ['id'], true),
  index('app', 'idx_email', ['email'], true)
]);

/**
 * Pointing at a table at the foot of a long statement narrows the index card
 * to that table, and the card stays beside the pointer rather than at the top
 * of the page. Narrowing it must not move the page either: that moved the SQL
 * out from under the pointer, and the card flickered between narrowed and whole.
 */
test('the relevant indexes stay in view, and steady, when hovering the foot of a long statement',
  async ({page}) => {
    await page.setViewportSize({width: 1400, height: 800});
    await stubApi(page);
    const json = (body: unknown) => ({contentType: 'application/json', body: JSON.stringify(body)});
    await page.route(`${SCOPED}/analyzed-sql/${ORDERS.normalizedDigest}`, route => route.fulfill(json(LONG)));
    await page.route(`${SCOPED}/analyzed-sql/${ORDERS.normalizedDigest}/sources`, route => route.fulfill(json([])));
    await page.route(`${SCOPED}/metadata/table/app/orders`, route => route.fulfill(json(ORDERS_TABLE)));
    await page.route(`${SCOPED}/metadata/table/app/users`, route => route.fulfill(json(USERS_TABLE)));

    await page.goto(`/analyzed-sql/${ORDERS.normalizedDigest}`);
    const panels = page.locator('.idx-panel');
    await expect(panels).toHaveCount(2);

    // the `o` qualifying status, on the statement's last line
    const target = page.locator('.sql-display [data-sqt]').last();
    await target.scrollIntoViewIfNeeded();
    const scroller = page.locator('.main-content');
    const before = await scroller.evaluate(e => e.scrollTop);
    expect(before).toBeGreaterThan(0);
    await target.hover();

    await expect(panels).toHaveCount(1);
    await expect(panels.first()).toContainText('idx_status');
    await expect(panels.first()).not.toContainText('idx_other_0');

    const card = await page.locator('.idx-card').boundingBox();
    expect(card).not.toBeNull();
    expect(card!.y).toBeGreaterThanOrEqual(0);
    expect(card!.y).toBeLessThan(800);

    // steady: the page did not move, and the card stays narrowed
    await page.waitForTimeout(300);
    expect(await scroller.evaluate(e => e.scrollTop)).toBe(before);
    await expect(panels).toHaveCount(1);
  });
