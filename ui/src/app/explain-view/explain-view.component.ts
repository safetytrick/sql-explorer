import {ExplainFinding, explainFindings} from './explain-findings';
import {Component, OnInit} from '@angular/core';
import {DecimalPipe} from '@angular/common';
import {RouterLink} from '@angular/router';
import {FormsModule} from '@angular/forms';
import {SchemaLensService} from '../schema-lens.service';
import {ExplainRow, ParsedExplain, parseExplain} from './explain-parser';

/**
 * Paste-and-inspect view for MySQL EXPLAIN output (tabular, \G vertical, or
 * FORMAT=JSON — sniffed automatically). Rows are annotated: full scans and
 * keyless reads are flagged, filesort/temporary usage is badged, and table
 * names link to the table lens when the name is unique in the loaded schema.
 */
@Component({
    selector: 'app-explain-view',
    templateUrl: './explain-view.component.html',
    styleUrls: ['./explain-view.component.scss'],
    imports: [FormsModule, RouterLink, DecimalPipe]
})
export class ExplainViewComponent implements OnInit {

  text = '';
  parsed: ParsedExplain = { rows: [], format: 'unknown', error: null };
  /** The plan read back in words, worst first (sqlx-2ko.8). */
  findings: ExplainFinding[] = [];

  /** lower-cased table name → schemas containing it (for lens links). */
  private schemasByTable = new Map<string, string[]>();

  constructor(private schemaLensService: SchemaLensService) { }

  ngOnInit() {
    this.schemaLensService.getSchemaTree().subscribe(tree => {
      const map = new Map<string, string[]>();
      for (const schema of tree.schemas) {
        for (const table of schema.tables) {
          const key = table.name.toLowerCase();
          map.set(key, [...(map.get(key) ?? []), schema.name]);
        }
      }
      this.schemasByTable = map;
    });
  }

  onTextChange(value: string) {
    this.text = value;
    this.parsed = parseExplain(value);
    this.findings = explainFindings(this.parsed.rows);
  }

  /** The lens route for a table name that resolves to exactly one schema. */
  lensLink(table: string | null): string[] | null {
    if (!table || table.startsWith('<')) {   // <derived2>, <union1,2>, …
      return null;
    }
    const schemas = this.schemasByTable.get(table.toLowerCase());
    return schemas && schemas.length === 1 ? ['/table', schemas[0], table] : null;
  }

  /** rows × filtered% — the optimizer's estimate of rows surviving this step. */
  estimatedRows(row: ExplainRow): number | null {
    if (row.rows === null) {
      return null;
    }
    return row.filtered === null ? row.rows : Math.round(row.rows * row.filtered / 100);
  }

  fullScan(row: ExplainRow): boolean {
    return row.type?.toUpperCase() === 'ALL';
  }

  indexScan(row: ExplainRow): boolean {
    return row.type?.toLowerCase() === 'index';
  }

  keyless(row: ExplainRow): boolean {
    return row.key === null && row.table !== null && !row.table.startsWith('<');
  }

  extraBadges(row: ExplainRow): string[] {
    if (!row.extra) {
      return [];
    }
    return ['Using filesort', 'Using temporary'].filter(flag => row.extra!.includes(flag));
  }
}
