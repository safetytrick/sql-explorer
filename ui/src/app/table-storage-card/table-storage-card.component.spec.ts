import {ComponentFixture, TestBed} from '@angular/core/testing';
import {TableStorageCardComponent} from './table-storage-card.component';
import {TableStorageStats} from '../api-types';
import {summarizeStorage} from './storage-summary';

function stats(server: string, rows: number | null, extra: Partial<TableStorageStats> = {}): TableStorageStats {
  return {
    server, readAt: '2026-11-01T00:00:00Z', rowsEstimate: rows, dataLength: 163840, indexLength: 16384,
    dataFree: 0, avgRowLength: 120, autoIncrement: 1301, rowFormat: 'Dynamic', engine: 'InnoDB',
    createTime: '2026-01-02T03:04:05Z', updateTime: null, tableCollation: 'utf8mb4_0900_ai_ci', ...extra
  };
}

describe('summarizeStorage', () => {
  it('adds up the primaries and leaves replicas out', () => {
    const summary = summarizeStorage(
      [stats('shard1', 1000), stats('shard2', 500), stats('shard1-replica', 1000)],
      {'shard1-replica': ['replica', 'sampling'], shard1: ['primary']});

    expect(summary.rows).toBe(1500);
    expect(summary.data).toBe(2 * 163840);
    expect(summary.excluded.map(s => s.server)).toEqual(['shard1-replica']);
    expect(summary.avgRow).toBe(Math.round(2 * 163840 / 1500));
  });

  it('takes the highest next id, the earliest creation and the latest update', () => {
    const summary = summarizeStorage([
      stats('a', 1, {autoIncrement: 10, createTime: '2026-02-01T00:00:00Z', updateTime: '2026-11-01T00:00:00Z'}),
      stats('b', 1, {autoIncrement: 99, createTime: '2026-01-01T00:00:00Z', updateTime: '2026-11-05T00:00:00Z'})]);

    expect(summary.nextId).toBe(99);
    expect(summary.created).toBe('2026-01-01T00:00:00Z');
    expect(summary.updated).toBe('2026-11-05T00:00:00Z');
  });

  it('names every engine or collation when the primaries disagree', () => {
    const summary = summarizeStorage([stats('a', 1), stats('b', 1, {tableCollation: 'latin1_swedish_ci'})]);

    expect(summary.engines).toEqual(['InnoDB']);
    expect(summary.collations).toEqual(['latin1_swedish_ci', 'utf8mb4_0900_ai_ci']);
  });

  it('falls back to the replicas when nothing else reports the table', () => {
    const summary = summarizeStorage([stats('r', 7)], {r: ['replica']});

    expect(summary.rows).toBe(7);
    expect(summary.excluded).toEqual([]);
  });
});

describe('TableStorageCardComponent', () => {
  let fixture: ComponentFixture<TableStorageCardComponent>;

  beforeEach(() => {
    fixture = TestBed.configureTestingModule({imports: [TableStorageCardComponent]})
      .createComponent(TableStorageCardComponent);
  });

  function render(rows: TableStorageStats[] | null, roles: Record<string, ('primary' | 'replica')[]> = {}): string {
    fixture.componentRef.setInput('roles', roles);
    fixture.componentRef.setInput('stats', rows);
    fixture.detectChanges();
    return fixture.nativeElement.textContent as string;
  }

  it('labels the row figure as an estimate', () => {
    const text = render([stats('app-replica', 1200)]);

    expect(text).toContain('rows (est.)');
    expect(text).toContain('≈1.2k');
    expect(text).toContain('160 KiB');
    expect(text).toContain('InnoDB');
    expect(text).toContain('captured');
    // the rest of the figures are a click away, never on the summary
    const summary = fixture.nativeElement.querySelector('dl.summary').textContent as string;
    expect(summary).not.toContain('next id');
    expect(summary).not.toContain('free');
    expect(summary).not.toContain('created');
  });

  it('totals the primaries and folds each server away', () => {
    const text = render([stats('shard1', 1000), stats('shard2', 500)]);

    expect(text).toContain('total of 2 primaries');
    expect(text).toContain('≈1.5k');
    const details = fixture.nativeElement.querySelector('details') as HTMLDetailsElement;
    expect(details.open).toBeFalse();
    expect(details.textContent).toContain('shard2');
  });

  it('says why there is nothing', () => {
    expect(render(null)).toContain('no size figures');
  });
});
