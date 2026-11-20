import {Component, DestroyRef, inject, OnInit} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {NgTemplateOutlet} from '@angular/common';
import {HttpClient} from '@angular/common/http';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {skip} from 'rxjs';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {TableColumn, TableIndex, TableStructure} from '../api-types';
import {EnvironmentService} from '../environment.service';
import {SqlHighlightDirective} from '../sql-highlight/sql-highlight.directive';
import {TableWorkloadComponent} from '../table-workload/table-workload.component';
import {IndexCardComponent} from '../index-card/index-card.component';
import {TableStorageCardComponent} from '../table-storage-card/table-storage-card.component';
import {ForeignKeyDetail, IndexUsage, SchemaLensService} from '../schema-lens.service';
import {decodePreflightParams, encodePreflightParams, Preflight, PREFLIGHT_ENDPOINT} from '../ddl-preflight/ddl-preflight';
import {PreflightResultComponent} from '../ddl-preflight/preflight-result.component';
import {
  backingIndex, BUILDER_ALGORITHMS, BUILDER_OPS, BuilderAlgorithm, BuilderOp, BuilderState, builderMissing,
  buildStatement, declaredType, EMPTY_BUILDER, fromExistingColumn, suggestIndexName
} from './ddl-builder';

/** Whether the CREATE TABLE statement is open; omitted when closed. */
const CREATE_PARAM = 'var-create';
/** The builder's operation; omitted at its default. */
const OP_PARAM = 'var-op';
/** The builder's ALGORITHM=; omitted when MySQL is left to choose. */
const ALG_PARAM = 'var-alg';

export const CHARSETS = ['utf8mb4', 'utf8mb3', 'latin1', 'ascii', 'binary'];
export const COLLATIONS = ['utf8mb4_0900_ai_ci', 'utf8mb4_0900_as_ci', 'utf8mb4_0900_as_cs', 'utf8mb4_0900_bin',
  'utf8mb4_bin', 'utf8mb4_general_ci', 'utf8mb4_unicode_ci', 'latin1_swedish_ci', 'ascii_general_ci'];

/**
 * Planning a schema change to one table (`/table/:schema/:name/ddl`): a small
 * builder for the statement, the pre-flight verdict on it, how the table is
 * written and what a DDL would wait behind, and the table's own definition
 * to build against. Nothing is ever executed.
 *
 * <p>The statement and threshold travel in the link exactly as on Admin → DDL
 * pre-flight (`var-ddl`, `var-long`), so one check reads the same on both.
 */
@Component({
  selector: 'app-table-ddl',
  templateUrl: './table-ddl.component.html',
  styleUrls: ['./table-ddl.component.scss', '../ddl-preflight/ddl-preflight.component.scss'],
  imports: [FormsModule, NgTemplateOutlet, RouterLink, SqlHighlightDirective, PreflightResultComponent, TableWorkloadComponent,
    IndexCardComponent, TableStorageCardComponent]
})
export class TableDdlComponent implements OnInit {
  readonly ops = BUILDER_OPS;
  readonly algorithms = BUILDER_ALGORITHMS;
  readonly charsets = CHARSETS;
  readonly collations = COLLATIONS;
  protected readonly declaredType = declaredType;

  schema = '';
  table = '';

  structure: TableStructure | null = null;
  /** The indexes as the table page shows them, with the hints that name each. */
  indexUsage: IndexUsage[] = [];
  /** The constraints this table owns (it is the child), the only ones it can drop. */
  foreignKeys: ForeignKeyDetail[] = [];
  /** Other tables' constraints that reference this one: any change here locks those tables too. */
  referencingKeys: ForeignKeyDetail[] = [];
  structureFailed = false;
  structureResolved = false;

  builder: BuilderState = {...EMPTY_BUILDER};
  ddl = '';
  longSeconds = 1;
  result: Preflight | null = null;
  busy = false;
  failed = false;

  showCreate = false;
  createStatement: string | null = null;
  createFailed = false;

  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly sqlService = inject(AnalyzedSqlService);
  private readonly schemaLensService = inject(SchemaLensService);
  private readonly environmentService = inject(EnvironmentService);

  ngOnInit() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      this.schema = params.get('schema') ?? '';
      this.table = params.get('name') ?? '';
      const query = this.route.snapshot.queryParamMap;
      const state = decodePreflightParams(query);
      this.ddl = state.ddl;
      this.longSeconds = state.longSeconds;
      const op = query.get(OP_PARAM) as BuilderOp | null;
      const algorithm = (query.get(ALG_PARAM) ?? '').toUpperCase() as BuilderAlgorithm;
      this.builder = {
        ...EMPTY_BUILDER,
        op: BUILDER_OPS.some(o => o.op === op) ? op! : EMPTY_BUILDER.op,
        algorithm: BUILDER_ALGORITHMS.includes(algorithm) ? algorithm : ''
      };
      this.showCreate = query.get(CREATE_PARAM) === '1';
      this.result = null;
      this.createStatement = null;
      this.load();
      if (this.ddl.trim()) { this.check(false); }
    });
    this.environmentService.current$.pipe(skip(1), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      if (this.schema) { this.load(); }
    });
  }

  private load() {
    this.structureResolved = false;
    this.structureFailed = false;
    this.sqlService.getTableStructure(this.schema, this.table).subscribe({
      next: structure => {
        this.structure = structure;
        this.structureResolved = true;
      },
      error: () => {
        this.structure = null;
        this.structureFailed = true;
        this.structureResolved = true;
      }
    });
    this.schemaLensService.getTableIndexes(this.schema, this.table).subscribe({
      next: indexes => { this.indexUsage = indexes ?? []; },
      error: () => { this.indexUsage = []; }
    });
    this.schemaLensService.getForeignKeys(this.schema, this.table).subscribe({
      next: fks => {
        this.foreignKeys = (fks ?? []).filter(fk => fk.direction === 'out' && !!fk.constraintName);
        this.referencingKeys = (fks ?? []).filter(fk => fk.direction === 'in');
      },
      error: () => { this.foreignKeys = []; this.referencingKeys = []; }
    });
    if (this.showCreate) { this.loadCreate(); }
  }

  get columns(): TableColumn[] {
    return [...(this.structure?.columns ?? [])].sort((a, b) => a.ordinal - b.ordinal);
  }

  get indexes(): TableIndex[] {
    return this.structure?.indices ?? [];
  }

  get statement(): string | null {
    return buildStatement(this.schema, this.table, this.builder);
  }

  get missing(): string[] {
    return builderMissing(this.builder);
  }

  setOp(op: BuilderOp) {
    this.builder = {...this.builder, op};
    this.writeUrl({[OP_PARAM]: op === EMPTY_BUILDER.op ? null : op});
  }

  setAlgorithm(algorithm: BuilderAlgorithm) {
    this.builder = {...this.builder, algorithm};
    this.writeUrl({[ALG_PARAM]: algorithm || null});
  }

  /** Picking the column to modify starts from its current definition. */
  pickColumn(name: string) {
    const column = this.columns.find(c => c.columnName === name);
    if (this.builder.op === 'modify-column' && column) {
      this.builder = fromExistingColumn(this.builder, column);
    } else {
      this.builder = {...this.builder, column: name};
    }
  }

  /** A column clicked in the reference: whatever the builder is building, aimed at that column. */
  useColumn(column: TableColumn) {
    switch (this.builder.op) {
      case 'add-index':
        this.toggleIndexColumn(column.columnName);
        return;
      case 'add-column':
        this.builder = {...this.builder, after: column.columnName};
        return;
      case 'convert':
      case 'drop-index':
      case 'drop-foreign-key':
        this.setOp('modify-column');
        break;
    }
    this.pickColumn(column.columnName);
  }

  useIndex(index: {name: string}) {
    this.setOp('drop-index');
    this.builder = {...this.builder, index: index.name};
  }

  useForeignKey(fk: ForeignKeyDetail) {
    this.setOp('drop-foreign-key');
    this.builder = {...this.builder, foreignKey: fk.constraintName ?? ''};
  }

  fkColumns(fk: ForeignKeyDetail): string {
    return fk.columns.map(c => c.fromColumn).join(', ');
  }

  /** The referenced side: `(id)`, or `(a, b)` for a composite key. */
  fkReferenced(fk: ForeignKeyDetail): string {
    return '(' + fk.columns.map(c => c.toColumn).join(', ') + ')';
  }

  /** The other table, schema-qualified only when it is in another schema. */
  fkOther(fk: ForeignKeyDetail): string {
    return fk.otherSchema && fk.otherSchema.toLowerCase() !== this.schema.toLowerCase()
      ? `${fk.otherSchema}.${fk.otherTable}` : fk.otherTable;
  }

  /** ON DELETE / ON UPDATE, leaving out MySQL's default (RESTRICT / NO ACTION). */
  fkRules(fk: ForeignKeyDetail): string {
    const rule = (what: string, value: string | null) =>
      value && !/^(RESTRICT|NO ACTION)$/i.test(value) ? `on ${what} ${value.toLowerCase()}` : '';
    return [rule('delete', fk.onDelete), rule('update', fk.onUpdate)].filter(Boolean).join(' · ');
  }

  /** The constraint picked to drop, as the snapshot describes it. */
  get pickedForeignKey(): ForeignKeyDetail | null {
    return this.foreignKeys.find(fk => fk.constraintName === this.builder.foreignKey) ?? null;
  }

  /** The index that stays behind once the picked constraint is dropped. */
  get leftIndex(): string | null {
    const fk = this.pickedForeignKey;
    return fk ? backingIndex(fk.columns.map(c => c.fromColumn), this.indexes) : null;
  }

  /** Drop the index the dropped constraint left, as the next change. */
  dropLeftIndex() {
    const index = this.leftIndex;
    if (!index) { return; }
    this.setOp('drop-index');
    this.builder = {...this.builder, index};
  }

  toggleIndexColumn(name: string) {
    const columns = this.builder.indexColumns.includes(name)
      ? this.builder.indexColumns.filter(c => c !== name)
      : [...this.builder.indexColumns, name];
    const suggested = suggestIndexName(this.table, this.builder.indexColumns);
    // keep a name the person typed; follow the columns while it is still the suggestion
    const index = !this.builder.index || this.builder.index === suggested
      ? suggestIndexName(this.table, columns) : this.builder.index;
    this.builder = {...this.builder, indexColumns: columns, index: columns.length ? index : ''};
  }

  /** Puts the built statement in the editor and checks it. */
  useStatement() {
    const statement = this.statement;
    if (!statement) { return; }
    this.ddl = statement;
    this.check();
  }

  check(writeUrl = true) {
    if (!this.ddl.trim() || this.busy) { return; }
    if (writeUrl) {
      const params = encodePreflightParams({ddl: this.ddl, longSeconds: this.longSeconds});
      this.writeUrl({'var-ddl': params['var-ddl'] ?? null, 'var-long': params['var-long'] ?? null});
    }
    this.busy = true;
    this.failed = false;
    this.http.post<Preflight>(PREFLIGHT_ENDPOINT, {sql: this.ddl, longStatementSeconds: this.longSeconds})
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: result => { this.result = result; this.busy = false; },
        error: () => { this.result = null; this.failed = true; this.busy = false; }
      });
  }

  toggleCreate() {
    this.showCreate = !this.showCreate;
    this.writeUrl({[CREATE_PARAM]: this.showCreate ? '1' : null});
    if (this.showCreate && this.createStatement === null) { this.loadCreate(); }
  }

  private loadCreate() {
    this.createFailed = false;
    this.sqlService.getTableCreateStatement(this.schema, this.table).subscribe({
      next: text => { this.createStatement = text; },
      error: () => { this.createFailed = true; }
    });
  }

  /** A statement in the result names a different table: the link goes there instead. */
  get otherTable(): boolean {
    const r = this.result;
    return !!r && !r.error && !!r.table
      && (r.table.toLowerCase() !== this.table.toLowerCase()
        || (!!r.schema && r.schema.toLowerCase() !== this.schema.toLowerCase()));
  }

  isIndexColumn(name: string): boolean {
    return this.builder.indexColumns.includes(name);
  }

  private writeUrl(params: Record<string, string | null>) {
    this.router.navigate([], {relativeTo: this.route, queryParams: params, queryParamsHandling: 'merge', replaceUrl: true});
  }
}
