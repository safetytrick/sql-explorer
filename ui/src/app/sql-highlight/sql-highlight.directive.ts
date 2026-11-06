import {Directive, ElementRef, EventEmitter, HostListener, inject, Input, OnChanges, Output} from '@angular/core';
import {Mark, Source} from './sql-scopes';
import {Token} from './sql-tokens';
import {paintSql, SchemaHint, TableColor} from './paint';

/**
 * `<code [sqlHighlight]="sql">`: highlights a statement with each table
 * instance in its own colour — its name, its alias, and every column
 * qualified by it — so the two sides of a self-join read apart.
 *
 * `(sqlTableHover)` reports the table instance under the pointer, for a page
 * to react to (the analyzed-sql page narrows its index pane). It is sticky
 * within the block: passing over a keyword or a space between two names keeps
 * the last instance rather than flickering through null, and only leaving the
 * block emits null. The directive itself draws nothing on hover.
 *
 * Lexical colours reuse the `hljs-*` class names so the existing theme rules
 * apply unchanged; table colours are `.sqt-c{n}` in styles.css.
 *
 * `[sqlSchema]` is optional: given the column lists of the query's tables, an
 * unqualified column in a multi-table SELECT is coloured too. Only pass what
 * the page already has loaded — see `SchemaHint`.
 *
 * Synchronous, unlike the highlight.js directive it replaced: the text is in the DOM as soon as
 * change detection runs. The DOM is built node by node with textContent, so
 * nothing in the statement is ever interpreted as markup.
 */
@Directive({
  selector: 'code[sqlHighlight]',
  host: {'class': 'hljs sql-hl'},
})
export class SqlHighlightDirective implements OnChanges {

  @Input() sqlHighlight: string | null | undefined;
  @Input() sqlSchema: readonly SchemaHint[] | null | undefined;
  @Output() readonly sqlTableHover = new EventEmitter<TableColor | null>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  ngOnChanges(): void {
    this.render(this.sqlHighlight ?? '', this.sqlSchema);
  }

  /** The instances of the current render, by id (`data-sqt`). */
  private tables: TableColor[] = [];
  private hovered: TableColor | null = null;

  @HostListener('mouseover', ['$event'])
  onOver(e: MouseEvent): void {
    const id = (e.target as HTMLElement | null)?.closest?.('[data-sqt]')?.getAttribute('data-sqt');
    const table = id == null ? undefined : this.tables.find(t => String(t.id) === id);
    if (table) { this.hover(table); }
  }

  @HostListener('mouseleave')
  onLeave(): void {
    this.hover(null);
  }

  private hover(table: TableColor | null): void {
    if (table === this.hovered) { return; }
    this.hovered = table;
    this.sqlTableHover.emit(table);
  }

  private render(sql: string, schema: readonly SchemaHint[] | null | undefined): void {
    const {analysis: {tokens, marks}, colors, tables} = paintSql(sql, schema);
    this.tables = tables;
    const doc = this.host.ownerDocument;
    const frag = doc.createDocumentFragment();
    tokens.forEach((t, i) => {
      const cls = classesFor(t, marks[i], colors);
      if (!cls) {
        frag.appendChild(doc.createTextNode(t.text));
        return;
      }
      const span = doc.createElement('span');
      span.className = cls;
      span.textContent = t.text;
      const s = marks[i]?.source;
      if (s) {
        span.setAttribute('data-sqt', String(s.id));
        span.title = describe(s);
      }
      frag.appendChild(span);
    });
    this.host.replaceChildren(frag);
    if (this.hovered) {
      // A re-render with a schema keeps the same instances (ids come from
      // syntax alone), so the hover survives it. Only if the statement changed
      // underneath is null emitted — deferred, since this runs in ngOnChanges
      // and the parent must not change state mid change-detection.
      const same = tables.find(t => t.id === this.hovered!.id && t.name === this.hovered!.name);
      if (same) {
        this.hovered = same;
      } else {
        queueMicrotask(() => this.hover(null));
      }
    }
  }
}

const ROLE_CLASS: Record<string, string> = {
  table: 'sqt-table', alias: 'sqt-alias', qualifier: 'sqt-qual', dot: 'sqt-dot', column: 'sqt-col',
};

export function classesFor(t: Token, m: Mark | undefined, colors: Map<Source, number>): string {
  if (m?.source) {
    const virtual = m.source.kind === 'table' ? '' : ' sqt-virtual';
    return `sqt ${ROLE_CLASS[m.role]} sqt-c${colors.get(m.source)}${virtual}`;
  }
  if (m?.role === 'schema') { return 'sqt-schema'; }
  if (m?.role === 'function') { return 'hljs-built_in'; }
  switch (t.kind) {
    case 'word': return t.keyword ? 'hljs-keyword' : '';
    case 'string': return 'hljs-string';
    case 'number':
    case 'placeholder': return 'hljs-number';
    case 'variable': return 'hljs-variable';
    case 'comment': return 'hljs-comment';
    case 'operator': return 'hljs-operator';
    default: return '';
  }
}

/** Hover text: what this name stands for. */
export function describe(s: Source): string {
  const full = s.schema ? `${s.schema}.${s.name}` : s.name;
  switch (s.kind) {
    case 'cte': return s.alias ? `${s.alias} → ${s.name} (common table expression)` : `${s.name} — common table expression`;
    case 'derived': return `${s.name} — derived table (subquery in FROM)`;
    default: return s.alias ? `${s.alias} → ${full}` : full;
  }
}
