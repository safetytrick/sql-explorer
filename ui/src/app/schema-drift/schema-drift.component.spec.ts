import {ComponentFixture, TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {provideRouter, Router} from '@angular/router';
import {CoverageService} from '../coverage.service';
import {EnvironmentService} from '../environment.service';
import {SchemaDriftComponent} from './schema-drift.component';

describe('SchemaDriftComponent', () => {
  let fixture: ComponentFixture<SchemaDriftComponent>;

  function render(report: unknown, fail = false) {
    TestBed.configureTestingModule({
      imports: [SchemaDriftComponent],
      providers: [
        provideRouter([]),
        {provide: CoverageService, useValue: {getCoverage: () => fail ? throwError(() => new Error('no')) : of(report)}},
        {provide: EnvironmentService, useValue: {current$: of('production')}}
      ]
    });
    fixture = TestBed.createComponent(SchemaDriftComponent);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('lists drifting tables with each cluster variant', () => {
    const text = render({drift: [{table: 'app_main.account', variants: [
      {present: true, clusters: ['shard_a']}, {present: false, clusters: ['shard_b']}]}]});
    expect(text).toContain('app_main.account');
    expect(text).toContain('absent on shard_b');
  });

  const altered = {drift: [{table: 'app_main.account', variants: [
    {present: true, clusters: ['shard_a']},
    {present: true, clusters: ['shard_b'], differences: [
      {kind: 'column', name: 'site_id', base: null, other: 'bigint NULL · position 2'},
      {kind: 'index', name: 'idx_site', base: null, other: 'INDEX (site_id)'}]}]}]};

  it('says what kind of drift each row is before it is opened', () => {
    const text = render(altered);
    expect(text).toContain('1 column, 1 index');
    expect(text).not.toContain('bigint NULL');
  });

  it('opens a row to show each difference against the definition lookups use, and keeps it in the URL', () => {
    render(altered);
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.drift-row')!.click();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('site_id');
    expect(text).toContain('bigint NULL · position 2');
    expect(text).toContain('INDEX (site_id)');
    expect(navigate.calls.mostRecent().args[1]?.queryParams).toEqual({'var-open': 'app_main.account'});
  });

  it('says a table is absent rather than showing an empty diff', () => {
    render({drift: [{table: 'app_main.audit', variants: [
      {present: true, clusters: ['shard_a']}, {present: false, clusters: ['shard_b']}]}]});
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.drift-row')!.click();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('does not exist on this cluster');
  });

  it('says so when nothing drifts', () => {
    expect(render({drift: []})).toContain('defines its tables the same way');
  });

  it('does not claim no drift when the report failed', () => {
    expect(render(null, true)).toContain('unknown');
  });
});
