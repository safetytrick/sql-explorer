import {Component} from '@angular/core';
import {FormsModule, ReactiveFormsModule, UntypedFormControl} from '@angular/forms';
import {SqlHighlightDirective} from '../sql-highlight/sql-highlight.directive';
import {AnalyzedSqlService} from '../analyzed-sql.service';

/** What the backend prefixes when it could not parse the statement it was asked to normalize. */
const PARSE_ERROR_PREFIX = '/* parse error */\n';

@Component({
  selector: 'app-query-utilities',
  templateUrl: './query-utilities.component.html',
  styleUrls: ['./query-utilities.component.scss'],
  imports: [SqlHighlightDirective, FormsModule, ReactiveFormsModule]
})
export class QueryUtilitiesComponent {
  inputControl = new UntypedFormControl('');
  /** Null until something has run; the result card is not drawn before then. */
  result: string | null = null;
  resultLabel = '';
  parseError = false;
  busy = false;
  failed = false;
  copied = false;

  constructor(public sqlService: AnalyzedSqlService) { }

  doNormalize() {
    this.run('Normalized', () => this.sqlService.normalizeQuery(this.inputControl.value), response => {
      const text = response.text ?? '';
      this.parseError = text.startsWith(PARSE_ERROR_PREFIX);
      return this.parseError ? text.slice(PARSE_ERROR_PREFIX.length) : text;
    });
  }

  doClean() {
    this.run('Cleaned', () => this.sqlService.cleanQuery(this.inputControl.value), response => response.sql);
  }

  copy() {
    if (this.result === null) { return; }
    navigator.clipboard?.writeText(this.result).then(() => {
      this.copied = true;
      setTimeout(() => this.copied = false, 1500);
    });
  }

  private run<T>(label: string, request: () => import('rxjs').Observable<T>, read: (response: T) => string) {
    if (!this.inputControl.value || this.busy) { return; }
    this.busy = true;
    this.failed = false;
    this.parseError = false;
    request().subscribe({
      next: response => {
        this.result = read(response);
        this.resultLabel = label;
        this.busy = false;
      },
      error: () => {
        this.result = null;
        this.failed = true;
        this.busy = false;
      }
    });
  }
}
