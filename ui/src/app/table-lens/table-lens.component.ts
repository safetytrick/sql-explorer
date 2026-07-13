import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DecimalPipe } from '@angular/common';
import {
  ColumnUsage,
  CoQueriedTable,
  ForeignKeyDetail,
  SchemaLensService,
  TableIndex,
  TableStats
} from '../schema-lens.service';
import { DurationPipe } from '../duration.pipe';
import { TagViewComponent } from '../tag-view/tag-view.component';

@Component({
  selector: 'app-table-lens',
  templateUrl: './table-lens.component.html',
  styleUrls: ['./table-lens.component.scss'],
  imports: [RouterLink, DecimalPipe, DurationPipe, TagViewComponent]
})
export class TableLensComponent implements OnInit {

  schema = '';
  table = '';

  stats: TableStats | null = null;
  columns: ColumnUsage[] = [];
  coQueried: CoQueriedTable[] = [];
  foreignKeys: ForeignKeyDetail[] = [];
  indexes: TableIndex[] = [];

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
    this.schemaLensService.getTableStats(this.schema, this.table).subscribe(stats => this.stats = stats);
    this.schemaLensService.getTableColumns(this.schema, this.table).subscribe(columns => this.columns = columns);
    this.schemaLensService.getCoQueried(this.schema, this.table).subscribe(coQueried => this.coQueried = coQueried);
    this.schemaLensService.getForeignKeys(this.schema, this.table).subscribe(fks => this.foreignKeys = fks);
    this.schemaLensService.getTableStructure(this.schema, this.table)
      .subscribe(structure => this.indexes = structure?.indices || []);
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
