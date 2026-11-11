import {TestBed} from '@angular/core/testing';
import {provideRouter, Router} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {of} from 'rxjs';
import {CollationsComponent} from './collations.component';
import {CollationReport, CollationsService, TableEncoding} from './collations';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {EnvironmentService} from '../environment.service';

function table(schema: string, name: string, collations: string[]): TableEncoding {
  return {
    schema, table: name, view: false, textColumns: collations.length,
    predominantCharset: 'utf8mb4', predominantCollation: collations[0] ?? null,
    mixedCharsets: false, mixedCollations: new Set(collations).size > 1,
    encodings: collations.map((collation, i) => ({characterSet: 'utf8mb4', collation, columns: [`c${i}`]})),
    outliers: collations.slice(1).map((collation, i) => ({column: `c${i + 1}`, characterSet: 'utf8mb4', collation}))
  };
}

const REPORT: CollationReport = {
  tables: [
    table('app_main', 'account', ['utf8mb4_0900_ai_ci', 'utf8mb4_bin']),
    table('app_main', 'site', ['utf8mb4_0900_ai_ci']),
    table('app_audit', 'account_site', ['utf8mb4_0900_ai_ci'])
  ],
  joins: []
};

describe('CollationsComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{path: 'collations', component: CollationsComponent}]),
        {provide: CollationsService, useValue: {getReport: () => of(REPORT)}},
        {provide: AnalyzedSqlService, useValue: {getTagsForTargets: () => of([])}},
        {provide: EnvironmentService, useValue: {current$: of('regression')}}
      ]
    });
  });

  async function open(url: string) {
    const harness = await RouterTestingHarness.create(url);
    harness.detectChanges();
    return {
      component: harness.routeDebugElement!.componentInstance as CollationsComponent,
      text: () => (harness.routeNativeElement as HTMLElement).textContent ?? '',
      harness
    };
  }

  it('groups the tables and flags the one that mixes collations', async () => {
    const {component, text} = await open('/collations');

    expect(component.groups.length).toBe(2);
    expect(component.mixedCount).toBe(1);
    expect(text()).toContain('mixed');
    expect(text()).toContain('c1 (utf8mb4_bin)');
  });

  it('restores its filters from a shared link', async () => {
    const {component} = await open('/collations?var-flag=mixed');

    expect(component.filter.mixedOnly).toBeTrue();
    expect(component.groups.flatMap(group => group.tables.map(t => t.table))).toEqual(['account']);
  });

  it('honours the schema scope that travels between pages', async () => {
    const {component} = await open('/collations?excludeSchemas=app_audit');

    expect(component.inScope.map(t => t.table)).toEqual(['account', 'site']);
  });

  it('writes a filter back to the URL', async () => {
    const {component, harness} = await open('/collations');

    component.setFilter({collation: 'utf8mb4_bin'});
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/collations?var-collation=utf8mb4_bin');
  });
});
