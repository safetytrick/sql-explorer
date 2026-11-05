import {ComponentFixture, TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {provideRouter} from '@angular/router';
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

  it('says so when nothing drifts', () => {
    expect(render({drift: []})).toContain('defines its tables the same way');
  });

  it('does not claim no drift when the report failed', () => {
    expect(render(null, true)).toContain('unknown');
  });
});
