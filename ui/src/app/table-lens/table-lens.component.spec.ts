import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {TableLensComponent} from './table-lens.component';
import {COMMON_TEST_PROVIDERS} from '../testing/common-test-providers';
import {ColumnUsage} from '../schema-lens.service';

/**
 * The lens's drill-downs and collapse state are URL state: a shared link has
 * to reproduce the view, which means reading them back out of the params.
 */
describe('TableLensComponent drill-downs', () => {

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...COMMON_TEST_PROVIDERS,
        provideRouter([{ path: 'table/:schema/:name', component: TableLensComponent }])
      ]
    });
  });

  async function open(url: string): Promise<TableLensComponent> {
    const harness = await RouterTestingHarness.create(url);
    return harness.routeDebugElement!.componentInstance;
  }

  function column(name: string, roles: string[]): ColumnUsage {
    return {
      name, roles, type: 'int', size: 11, nullable: false, autoIncrement: false,
      primaryKey: false, foreignKey: false, characterSet: null, collation: null, queries: 3
    };
  }

  it('starts with no drill-down and both relationship cards collapsed', async () => {
    const component = await open('/table/app_other/user_profile');

    expect(component.drillColumn).toBeNull();
    expect(component.joinWith).toBeNull();
    expect(component.fkOpen).toBe(false);
    expect(component.coOpen).toBe(false);
  });

  it('reads a column drill-down out of a shared link', async () => {
    const component = await open('/table/app_other/user_profile?columns=app_other.user_profile.user_id');

    expect(component.drillColumn).toBe('app_other.user_profile.user_id');
  });

  it('reads a join drill-down, ignoring the table itself among the scope', async () => {
    const component = await open('/table/app_other/group_member?tables=app_other.group_member,app_other.groups&tableMode=ALL');

    expect(component.joinWith).toBe('app_other.groups');
  });

  it('restores expanded relationship cards from the presentation params', async () => {
    const component = await open('/table/app_other/user_profile?var-fk=1&var-co=1');

    expect(component.fkOpen).toBe(true);
    expect(component.coOpen).toBe(true);
  });

  /**
   * Only a column that joins or filters can constrain a query, so only those
   * drill — a select-only column would open an empty list that reads as broken.
   */
  it('offers the drill-down only for join and criteria columns', async () => {
    const component = await open('/table/app_other/user_profile');

    expect(component.isDrillable(column('user_id', ['join']))).toBe(true);
    expect(component.isDrillable(column('status', ['criteria']))).toBe(true);
    expect(component.isDrillable(column('email', ['criteria', 'select']))).toBe(true);
    expect(component.isDrillable(column('twitter_link', ['select']))).toBe(false);
    expect(component.isDrillable(column('unused', []))).toBe(false);
  });

  it('translates the view into workbench params without re-mapping anything', async () => {
    const component = await open('/table/app_other/user_profile?columns=app_other.user_profile.user_id');
    const params = component.workbenchParams();

    expect(params['columns']).toBe('app_other.user_profile.user_id');
    expect(params['tables']).toBe('app_other.user_profile');
  });
});
