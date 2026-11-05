/**
 * A tolerant MySQL tokenizer for display.
 *
 * It never throws and never drops text: concatenating every token's `text`
 * gives back the input exactly. That is the whole contract a highlighter
 * needs, and it is what a real parser cannot promise here — Performance
 * Schema digests carry `?` placeholders, `(...)` elisions and can be
 * truncated mid-statement, and the highlighter still has to show them.
 */

export type TokenKind =
  | 'ws'
  | 'comment'
  | 'string'
  | 'number'
  | 'placeholder'   // `?`
  | 'variable'      // @user_var, @@system_var
  | 'word'          // bare identifier or keyword, decided by `keyword`
  | 'quoted'        // `backtick identifier`
  | 'dot'
  | 'comma'
  | 'semi'
  | 'lparen'
  | 'rparen'
  | 'operator'
  | 'other';

export interface Token {
  kind: TokenKind;
  text: string;
  /** Uppercased text when this is a bare word in the keyword set. */
  keyword?: string;
  /** Identifier value: a bare word as written, a quoted one unquoted. */
  ident?: string;
}

/**
 * Words treated as keywords when bare. Reserved words plus the clause words
 * the scope pass navigates by; deliberately *not* every MySQL keyword, because
 * non-reserved ones (`status`, `date`, `name`, `type`) are column names far
 * more often than they are syntax.
 */
export const KEYWORDS: ReadonlySet<string> = new Set([
  'ADD', 'ALL', 'ALTER', 'AND', 'AS', 'ASC', 'BETWEEN', 'BINARY', 'BOTH', 'BY',
  'CALL', 'CASE', 'CAST', 'CHECK', 'COLLATE', 'COLUMN', 'CONSTRAINT', 'CREATE',
  'CROSS', 'CURRENT_DATE', 'CURRENT_TIME', 'CURRENT_TIMESTAMP', 'DEFAULT',
  'DELETE', 'DESC', 'DISTINCT', 'DISTINCTROW', 'DIV', 'DROP', 'DUAL',
  'DUPLICATE', 'ELSE', 'END', 'ESCAPE', 'EXCEPT', 'EXISTS', 'EXPLAIN', 'FALSE',
  'FOR', 'FORCE', 'FOREIGN', 'FROM', 'FULL', 'GROUP', 'HAVING', 'HIGH_PRIORITY',
  'IF', 'IGNORE', 'IN', 'INDEX', 'INNER', 'INSERT', 'INTERSECT', 'INTERVAL',
  'INTO', 'IS', 'JOIN', 'KEY', 'LATERAL', 'LEFT', 'LIKE', 'LIMIT', 'LOCK',
  'LOW_PRIORITY', 'MOD', 'NATURAL', 'NOT', 'NULL', 'OFFSET', 'ON', 'OR',
  'ORDER', 'OUTER', 'OVER', 'PARTITION', 'PRIMARY', 'QUICK', 'RECURSIVE',
  'REFERENCES', 'REGEXP', 'REPLACE', 'RIGHT', 'RLIKE', 'ROLLUP', 'SELECT',
  'SET', 'SHARE', 'SHOW', 'SQL_CALC_FOUND_ROWS', 'SQL_NO_CACHE',
  'STRAIGHT_JOIN', 'TABLE', 'THEN', 'TO', 'TRUE', 'TRUNCATE', 'UNION',
  'UNIQUE', 'UNKNOWN', 'UPDATE', 'USE', 'USING', 'VALUES', 'VIEW', 'WHEN',
  'WHERE', 'WINDOW', 'WITH', 'XOR',
]);

const WORD_START = /[A-Za-z_$\u0080-￿]/;
const WORD_PART = /[A-Za-z0-9_$\u0080-￿]/;
const DIGIT = /[0-9]/;
const OPERATORS = ['<=>', '->>', '<=', '>=', '<>', '!=', ':=', '||', '&&', '<<', '>>', '->'];

export function tokenize(sql: string): Token[] {
  const out: Token[] = [];
  const n = sql.length;
  let i = 0;

  const push = (kind: TokenKind, end: number, extra: Partial<Token> = {}) => {
    out.push({kind, text: sql.slice(i, end), ...extra});
    i = end;
  };
  /** End of a quoted run starting at i, honouring doubled and backslash escapes. */
  const quotedEnd = (q: string, backslash: boolean): number => {
    let j = i + 1;
    while (j < n) {
      const c = sql[j];
      if (backslash && c === '\\') { j += 2; continue; }
      if (c === q) {
        if (sql[j + 1] === q) { j += 2; continue; }
        return j + 1;
      }
      j++;
    }
    return n;   // unterminated (truncated digest): the rest is the literal
  };

  while (i < n) {
    const c = sql[i];
    const c2 = sql[i + 1];

    if (/\s/.test(c)) {
      let j = i + 1;
      while (j < n && /\s/.test(sql[j])) { j++; }
      push('ws', j);
    } else if ((c === '-' && c2 === '-' && (i + 2 >= n || /\s/.test(sql[i + 2]))) || c === '#') {
      const nl = sql.indexOf('\n', i);
      push('comment', nl < 0 ? n : nl);
    } else if (c === '/' && c2 === '*') {
      const close = sql.indexOf('*/', i + 2);
      push('comment', close < 0 ? n : close + 2);
    } else if (c === '\'' || c === '"') {
      push('string', quotedEnd(c, true));
    } else if (c === '`') {
      const end = quotedEnd('`', false);
      const inner = sql.slice(i + 1, sql[end - 1] === '`' && end - 1 > i ? end - 1 : end);
      push('quoted', end, {ident: inner.replace(/``/g, '`')});
    } else if (DIGIT.test(c) || (c === '.' && DIGIT.test(c2 ?? '') && !prevIsIdentLike(out))) {
      push('number', numberEnd(sql, i));
    } else if (c === '?') {
      push('placeholder', i + 1);
    } else if (c === '@') {
      let j = i + 1;
      if (sql[j] === '@') { j++; }
      while (j < n && (WORD_PART.test(sql[j]) || sql[j] === '.')) { j++; }
      push('variable', j);
    } else if (WORD_START.test(c)) {
      let j = i + 1;
      while (j < n && WORD_PART.test(sql[j])) { j++; }
      const word = sql.slice(i, j);
      const upper = word.toUpperCase();
      push('word', j, KEYWORDS.has(upper) ? {keyword: upper, ident: word} : {ident: word});
    } else if (c === '.' && sql.startsWith('...', i)) {
      push('other', i + 3);   // the digest's `(...)` elision
    } else if (c === '.') {
      push('dot', i + 1);
    } else if (c === ',') {
      push('comma', i + 1);
    } else if (c === ';') {
      push('semi', i + 1);
    } else if (c === '(') {
      push('lparen', i + 1);
    } else if (c === ')') {
      push('rparen', i + 1);
    } else {
      const op = OPERATORS.find(o => sql.startsWith(o, i));
      push(op || /[=<>!+\-*/%&|^~:]/.test(c) ? 'operator' : 'other', i + (op?.length ?? 1));
    }
  }
  return out;
}

function prevIsIdentLike(out: Token[]): boolean {
  const prev = out[out.length - 1];
  return !!prev && (prev.kind === 'word' || prev.kind === 'quoted' || prev.kind === 'rparen');
}

function numberEnd(sql: string, i: number): number {
  const hex = /^0x[0-9a-f]+/i.exec(sql.slice(i, i + 64));
  if (hex) { return i + hex[0].length; }
  const m = /^(\d*\.?\d+|\d+\.)(e[+-]?\d+)?/i.exec(sql.slice(i, i + 256));
  let end = i + (m ? m[0].length : 1);
  // `1abc` is an identifier in MySQL; don't split it into a number and a word
  while (end < sql.length && WORD_PART.test(sql[end])) { end++; }
  return end;
}
