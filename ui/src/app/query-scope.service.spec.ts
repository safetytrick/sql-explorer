import {QueryScopeService} from './query-scope.service';

/** The schema tree's box: off → include → exclude → off, alt-click excluding at once (sqlx-fph). */
describe('QueryScopeService scope cycling', () => {

  it('cycles a table through include, exclude and off', () => {
    const service = new QueryScopeService();
    service.cycleTable('app_main.account');
    expect(service.scope.tables).toEqual(['app_main.account']);
    service.cycleTable('app_main.account');
    expect(service.scope.tables).toEqual([]);
    expect(service.scope.excludeTables).toEqual(['app_main.account']);
    expect(service.isTableExcluded('app_main.account')).toBeTrue();
    service.cycleTable('app_main.account');
    expect(service.scope.excludeTables).toEqual([]);
  });

  it('excludes at once on alt-click, from off or from include', () => {
    const service = new QueryScopeService();
    service.cycleSchema('App_Main', true);
    expect(service.scope.excludeSchemas).toEqual(['app_main']);
    service.cycleSchema('app_billing');
    service.cycleSchema('app_billing', true);
    expect(service.scope.schemas).toEqual([]);
    expect(service.scope.excludeSchemas).toEqual(['app_main', 'app_billing']);
  });

  it('removes a table from either side', () => {
    const service = new QueryScopeService();
    service.cycleTable('app_main.note', true);
    service.removeTable('app_main.note');
    expect(service.scope.excludeTables).toEqual([]);
  });
});
