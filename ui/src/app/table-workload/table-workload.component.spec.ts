import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {of, throwError} from 'rxjs';
import {TableWorkloadComponent} from './table-workload.component';
import {fmtPerDay, fmtTime, fmtWindow, LockRisk, TableWorkloadService, WriteRate} from './table-workload.service';

const NO_WRITES: WriteRate = {
  executions: 0, rowsAffected: 0, executionsPerDay: 0, rowsPerDay: 0, from: null, to: null, byType: [], statements: []
};

const WRITES: WriteRate = {
  executions: 1000, rowsAffected: 1000, executionsPerDay: 100, rowsPerDay: 100,
  from: '2026-05-01T00:00:00', to: '2026-05-11T00:00:00',
  byType: [{type: 'INSERT', statements: 1, executions: 1000, rowsAffected: 1000, executionsPerDay: 100, rowsPerDay: 100}],
  statements: [{
    digest: 'd-ins', type: 'INSERT', query: 'insert into account (id) values (?)', multiTable: false,
    executions: 1000, rowsAffected: 1000, executionsPerDay: 100, rowsPerDay: 100, windowDays: 10,
    firstSeen: '2026-05-01T00:00:00', lastSeen: '2026-05-11T00:00:00'
  }]
};

const RISK: LockRisk = {
  statementCount: 2, longThresholdMs: 1000, longStatements: 1, longExecutionsPerDay: 2,
  maxTimeMs: 45_000, maxAverageTimeMs: 9_000, quietHoursAvailable: false,
  statements: [
    {digest: 'd-report', type: 'SELECT', query: 'select * from account', executions: 20, executionsPerDay: 2,
      averageTimeMs: 9000, maxTimeMs: 45_000, totalTimeMs: 180_000, lockTimeMs: 0, windowDays: 10},
    {digest: 'd-quick', type: 'SELECT', query: 'select id from account where id = ?', executions: 10_000,
      executionsPerDay: 1000, averageTimeMs: 2, maxTimeMs: 40, totalTimeMs: 20_000, lockTimeMs: 0, windowDays: 10}
  ]
};

describe('TableWorkloadComponent', () => {
  let fixture: ComponentFixture<TableWorkloadComponent>;

  function render(writes: WriteRate | null, risk: LockRisk | null): string {
    TestBed.configureTestingModule({
      imports: [TableWorkloadComponent],
      providers: [
        provideRouter([]),
        {provide: TableWorkloadService, useValue: {
          getWriteRate: () => writes ? of(writes) : throwError(() => new Error('no')),
          getLockRisk: () => risk ? of(risk) : throwError(() => new Error('no'))
        }}
      ]
    });
    fixture = TestBed.createComponent(TableWorkloadComponent);
    fixture.componentRef.setInput('schema', 'app_main');
    fixture.componentRef.setInput('table', 'account');
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('shows writes per day as an average, by type and statement', () => {
    const text = render(WRITES, RISK);
    expect(text).toContain('daily average');
    expect(text).toContain('INSERT');
    expect(text).toContain('100/day');
  });

  it('ranks the statements a DDL would wait behind and says quiet hours are not available', () => {
    const text = render(WRITES, RISK);
    expect(text).toContain('1 of 2 reach 1 s');
    expect(text).toContain('45 s');
    expect(text).toContain('Quiet hours are not available');
  });

  it('says plainly when nothing writes the table', () => {
    expect(render(NO_WRITES, {...RISK, statementCount: 0, statements: []}))
      .toContain('no INSERT, UPDATE, DELETE or REPLACE');
  });

  it('does not read a failed request as "no writes"', () => {
    const text = render(null, null);
    expect(text).toContain('could not be loaded');
    expect(text).not.toContain('no INSERT');
  });

  it('formats rates, times and windows compactly', () => {
    expect(fmtPerDay(0.03)).toBe('<0.1');
    expect(fmtPerDay(3.25)).toBe('3.3');
    expect(fmtPerDay(1480)).toBe('1.5k');
    expect(fmtTime(850)).toBe('850 ms');
    expect(fmtTime(1500)).toBe('1.5 s');
    expect(fmtTime(192_000)).toBe('3.2 min');
    expect(fmtWindow(0.25)).toBe('6 h');
    expect(fmtWindow(null)).toBe('?');
  });
});
