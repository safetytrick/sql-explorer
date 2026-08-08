import {Component, OnInit} from '@angular/core';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {
  ColumnUsage,
  CoQueriedTable,
  ForeignKeyDetail,
  SchemaLensService,
  TableIndex,
  TableStats,
  ViewColumnMeta
} from '../schema-lens.service';
import {DurationPipe} from '../duration.pipe';
import {TagViewComponent} from '../tag-view/tag-view.component';
import {parseColumnRef, parseTableRef} from '../refs';

@Component({
  selector: 'app-table-lens',
  templateUrl: './table-lens.component.html',
  styleUrls: ['./table-lens.component.scss'],
  imports: [RouterLink, DurationPipe, TagViewComponent]
})
export class TableLensComponent implements OnInit {

  schema = '';
  table = '';

  stats: TableStats | null = null;
  columns: ColumnUsage[] = [];
  coQueried: CoQueriedTable[] = [];
  foreignKeys: ForeignKeyDetail[] = [];
  indexes: TableIndex[] = [];
  isView = false;
  /** Base tables this view selects from (views only). */
  underlyingTables: { schema: string; table: string }[] = [];
  /** Per-column lineage (views only), keyed by normalized column name. */
  private viewLineage = new Map<string, ViewColumnMeta>();

  columnFilter: 'all' | 'used' | 'keys' = 'all';

  constructor(private route: ActivatedRoute, private schemaLensService: SchemaLensService) { }

  ngOnInit() {
    this.route.paramMap.subscribe(params => {
      this.schema = params.get('schema') || '';
      this.table = params.get('name') || '';
      this.load();
    });
  }

  private load() {
    this.stats = null;
    this.columns = [];
    this.coQueried = [];
    this.foreignKeys = [];
    this.indexes = [];
    this.isView = false;
    this.underlyingTables = [];
    this.viewLineage = new Map();
    this.schemaLensService.getTableStats(this.schema, this.table).subscribe(stats => this.stats = stats);
    this.schemaLensService.getTableColumns(this.schema, this.table).subscribe(columns => this.columns = columns);
    this.schemaLensService.getCoQueried(this.schema, this.table).subscribe(coQueried => this.coQueried = coQueried);
    this.schemaLensService.getForeignKeys(this.schema, this.table).subscribe(fks => this.foreignKeys = fks);
    this.schemaLensService.getTableStructure(this.schema, this.table)
      .subscribe(structure => {
        this.indexes = structure?.indices || [];
        this.isView = structure?.type?.toUpperCase() === 'VIEW';
        // refs arrive as "schema.table" strings; the old object-shaped filter
        // read undefined on both fields and dropped every one of them
        this.underlyingTables = (structure?.underlyingTables || [])
          .map(ref => parseTableRef(ref))
          .filter((ref): ref is { schema: string; table: string } => !!ref.schema && !!ref.table);
        this.viewLineage = new Map((structure?.viewColumns || [])
          .map(column => [column.name.toLowerCase(), column]));
      });
  }

  /** Split a lineage source string for display and linking. */
  sourceParts(source: string): { schema: string | null; table: string | null; column: string } {
    return parseColumnRef(source);
  }

  lineageFor(columnName: string): ViewColumnMeta | null {
    return this.viewLineage.get(columnName.toLowerCase()) ?? null;
  }

  visibleColumns(): ColumnUsage[] {
    switch (this.columnFilter) {
      case 'used': return this.columns.filter(column => column.queries > 0);
      case 'keys': return this.columns.filter(column => column.primaryKey || column.foreignKey);
      default: return this.columns;
    }
  }

  outgoingCount(): number {
    return this.foreignKeys.filter(fk => fk.direction === 'out').length;
  }

  incomingCount(): number {
    return this.foreignKeys.filter(fk => fk.direction === 'in').length;
  }

  readPercent(): number | null {
    if (!this.stats) { return null; }
    const total = this.stats.readExecutions + this.stats.writeExecutions;
    return total === 0 ? null : Math.round(this.stats.readExecutions / total * 100);
  }

  maxColumnQueries(): number {
    return Math.max(1, ...this.columns.map(column => column.queries));
  }

  fmtExecutions(value: number): string {
    if (value >= 1_000_000) { return (value / 1_000_000).toFixed(1) + 'M'; }
    if (value >= 1_000) { return Math.round(value / 1_000) + 'K'; }
    return String(value);
  }

  fmtMs(value: number | null): string {
    if (value === null || value === undefined) { return '—'; }
    return value >= 1000 ? (value / 1000).toFixed(1) + 's' : value.toFixed(1) + 'ms';
  }
}
