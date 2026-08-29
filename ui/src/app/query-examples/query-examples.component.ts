import {Component, Input, OnInit} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {RouterLink} from '@angular/router';
import {Highlight} from 'ngx-highlightjs';
import {HttpErrorResponse} from '@angular/common/http';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ExplainResult, QueryExample, QueryExampleRejected} from '../api-types';
import {
  buildStatement, ColumnType, isComplete, Placeholder, readPlaceholders
} from './placeholder-form';

/**
 * Newest first.
 *
 * <p>The server already orders by id, which is a UUIDv7 and therefore
 * chronological. Sorted again here so the order is a property of this list
 * rather than of the endpoint that happened to fill it — a newly added example
 * is spliced in locally without a refetch, and that path has no server
 * ordering to inherit.
 */
function byRecency(examples: QueryExample[]): QueryExample[] {
  return [...examples].sort((a, b) => b.createdOn.localeCompare(a.createdOn));
}

/**
 * Add and list example queries for one normalized query group.
 *
 * <p>The happy path here is trivial. Everything interesting is in the two ways
 * a submission can fail to mean what the person submitting it assumed, and
 * both come straight out of the endpoint's central rule: <b>the server derives
 * the digest from the statement rather than accepting one.</b>
 *
 * <ol>
 *   <li><b>The paste may not belong to this query.</b> The form cannot post to
 *       "the digest of the page you are on" — it posts the text, and the digest
 *       that comes back may be a different one. That is the feature working
 *       correctly, so it is shown plainly with a link to the group the example
 *       actually belongs to, never silently filed under the page it was typed
 *       on.</li>
 *   <li><b>The schema may be ambiguous.</b> An unqualified statement whose
 *       tables exist in several schemas has no single answer, and the server
 *       says so with the candidates. That becomes a picker over exactly those
 *       candidates rather than a free-text box, because the set of right
 *       answers is known and small.</li>
 * </ol>
 */
@Component({
  selector: 'app-query-examples',
  templateUrl: './query-examples.component.html',
  styleUrls: ['./query-examples.component.scss'],
  imports: [FormsModule, RouterLink, Highlight]
})
export class QueryExamplesComponent implements OnInit {

  /** The digest of the query this panel is showing examples for. */
  @Input({required: true}) digest!: string;

  /**
   * The query's own text, with its `?` placeholders — what the form is built
   * from. Without it the panel falls back to the paste box.
   */
  @Input() queryText: string | null = null;

  /** Column types for the tables this query touches, to type the inputs. */
  @Input() columnTypes: ColumnType[] = [];

  examples: QueryExample[] = [];
  loading = true;

  // ── the form ──
  open = false;

  /**
   * Placeholders read off {@link queryText}. The primary way to add an example:
   * everything but the values is fixed, so what it builds normalizes back to
   * this page's digest rather than drifting somewhere else the way a paste can.
   */
  placeholders: Placeholder[] = [];

  /**
   * The paste box, kept as the fallback for when someone already has a
   * statement — or when the query has no placeholders to fill, or its text
   * could not be read.
   */
  pasteMode = false;
  draft = '';
  description = '';
  submitting = false;

  /**
   * Set only after the server has told us the statement is ambiguous, and only
   * ever to one of the schemas it offered. Not a free-text field: the server
   * knows the candidates, so guessing is not a thing the UI should invite.
   */
  chosenSchema: string | null = null;
  candidateSchemas: string[] = [];

  /** A rejection to show, or null. */
  rejected: QueryExampleRejected | null = null;

  /**
   * An example that stored successfully but under a *different* digest than
   * this page's. Kept separate from `examples` because it does not belong in
   * this list — that is the whole point of showing it.
   */
  storedElsewhere: QueryExample | null = null;

  // ── live explain ──
  explaining: string | null = null;
  plans = new Map<string, ExplainResult>();
  explainErrors = new Map<string, string>();

  constructor(private sqlService: AnalyzedSqlService) { }

  ngOnInit() {
    this.reload();
  }

  reload() {
    this.loading = true;
    this.sqlService.getQueryExamples(this.digest).subscribe({
      next: examples => { this.examples = byRecency(examples); this.loading = false; },
      error: () => { this.examples = []; this.loading = false; }
    });
  }

  toggleForm() {
    this.open = !this.open;
    if (this.open) {
      this.placeholders = this.queryText ? readPlaceholders(this.queryText, this.columnTypes) : [];
      // nothing to fill in means the form has nothing to offer; the paste box
      // is the only useful control, so go straight to it
      this.pasteMode = this.placeholders.length === 0;
    } else {
      this.resetForm();
    }
  }

  /** Switch between filling the query's own placeholders and pasting a statement. */
  usePaste(paste: boolean) {
    this.pasteMode = paste;
    this.rejected = null;
  }

  /** The statement the filled form currently describes, for the preview. */
  get preview(): string {
    return this.queryText ? buildStatement(this.queryText, this.placeholders) : '';
  }

  get formComplete(): boolean {
    return this.placeholders.length > 0 && isComplete(this.placeholders);
  }

  addListValue(placeholder: Placeholder) {
    placeholder.values.push('');
  }

  removeListValue(placeholder: Placeholder, index: number) {
    placeholder.values.splice(index, 1);
    if (placeholder.values.length === 0) { placeholder.values.push(''); }
  }

  /** ngModel needs a stable identity per row; the index is it. */
  trackByIndex(index: number): number {
    return index;
  }

  submit() {
    // the form builds the statement; the paste box supplies one directly
    const statement = this.pasteMode ? this.draft : this.preview;
    if (!statement.trim() || this.submitting) { return; }
    this.submitting = true;
    this.rejected = null;
    this.storedElsewhere = null;

    this.sqlService.createQueryExample(statement, this.chosenSchema, this.description).subscribe({
      next: example => {
        this.submitting = false;
        if (example.normalizedDigest === this.digest) {
          // most-recent-first, matching the server's own ordering
          this.examples = byRecency([example, ...this.examples]);
          this.resetForm();
          this.open = false;
        } else {
          // Stored, and correctly — just not here. Saying so is the honest
          // rendering; filing it under this page would be the bug.
          this.storedElsewhere = example;
          this.resetForm();
        }
      },
      error: (response: HttpErrorResponse) => {
        this.submitting = false;
        const body = response.error as QueryExampleRejected | null;
        this.rejected = body ?? {reason: 'UNKNOWN', detail: 'the request failed', candidateSchemas: []};
        // seed the picker with exactly what the server offered
        this.candidateSchemas = body?.candidateSchemas ?? [];
        if (this.candidateSchemas.length && !this.chosenSchema) {
          this.chosenSchema = this.candidateSchemas[0];
        }
      }
    });
  }

  /** Plan a stored example against the active environment's server. */
  explain(example: QueryExample) {
    if (this.explaining) { return; }
    this.explaining = example.id;
    this.explainErrors.delete(example.id);

    this.sqlService.explainQueryExample(example.id).subscribe({
      next: result => {
        this.plans.set(example.id, result);
        this.explaining = null;
      },
      error: (response: HttpErrorResponse) => {
        this.explaining = null;
        const detail = response.error?.detail ?? 'the plan could not be fetched';
        // 403 is the environment declining rather than anything being broken,
        // and it is the common case, so it gets its own wording
        this.explainErrors.set(example.id,
          response.status === 403
            ? 'this environment does not allow EXPLAIN — ' + detail
            : detail);
      }
    });
  }

  planFor(example: QueryExample): ExplainResult | undefined {
    return this.plans.get(example.id);
  }

  explainErrorFor(example: QueryExample): string | undefined {
    return this.explainErrors.get(example.id);
  }

  /** Pretty-print the server's plan document; fall back to the raw string. */
  formatPlan(plan: string): string {
    try {
      return JSON.stringify(JSON.parse(plan), null, 2);
    } catch {
      return plan;
    }
  }

  dismissRejection() {
    this.rejected = null;
  }

  dismissStoredElsewhere() {
    this.storedElsewhere = null;
  }

  private resetForm() {
    this.placeholders = this.queryText ? readPlaceholders(this.queryText, this.columnTypes) : [];
    this.pasteMode = this.placeholders.length === 0;
    this.draft = '';
    this.description = '';
    this.chosenSchema = null;
    this.candidateSchemas = [];
    this.rejected = null;
  }
}
