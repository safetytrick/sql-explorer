import {Directive, ElementRef, HostListener, inject, Input, OnChanges} from '@angular/core';
import {Mark, Source} from './sql-scopes';
import {Token} from './sql-tokens';
import {paintSql, SchemaHint} from './paint';

/**
 * `<code [sqlHighlight]="sql">`: highlights a statement with each table
 * instance in its own colour — its name, its alias, and every column
 * qualified by it — so the two sides of a self-join read apart. Hovering any
 * of them lights up the rest of that instance.
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

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  ngOnChanges(): void {
    this.render(this.sqlHighlight ?? '', this.sqlSchema);
  }

  @HostListener('mouseover', ['$event'])
  onOver(e: MouseEvent): void {
    const id = (e.target as HTMLElement | null)?.closest?.('[data-sqt]')?.getAttribute('data-sqt');
    this.light(id ?? null);
  }

  @HostListener('mouseleave')
  onLeave(): void {
    this.light(null);
  }

  private lit: string | null = null;

  private light(id: string | null): void {
    if (id === this.lit) { return; }
    this.host.querySelectorAll('.sqt-hot').forEach(el => el.classList.remove('sqt-hot'));
    if (id !== null) {
      this.host.querySelectorAll(`[data-sqt="${id}"]`).forEach(el => el.classList.add('sqt-hot'));
    }
    this.lit = id;
  }

  private render(sql: string, schema: readonly SchemaHint[] | null | undefined): void {
    const {analysis: {tokens, marks}, colors} = paintSql(sql, schema);
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
    this.lit = null;
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
