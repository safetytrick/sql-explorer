import {Token, tokenize} from './sql-tokens';

/**
 * Which table each identifier in a statement belongs to — enough to colour a
 * query by table *instance*, so `a` and `b` in `account a JOIN account b` read
 * as two things.
 *
 * This is a display heuristic over a token stream, not a parser. It never
 * fails: anything it cannot place is simply left unmarked and renders in the
 * plain text colour. What it does place it places from syntax alone — there is
 * no schema here, so an unqualified column is only attributed when its SELECT
 * has exactly one table to attribute it to.
 */

export type SourceKind = 'table' | 'cte' | 'derived';

/** One table instance in a statement: a FROM/JOIN entry, a CTE, a derived table. */
export interface Source {
  id: number;
  kind: SourceKind;
  /** Table name, or the CTE / derived-table name. */
  name: string;
  schema?: string;
  alias?: string;
  /** What the colour is chosen from: the bare table name, lowercased. */
  colorKey: string;
}

export type Role = 'schema' | 'table' | 'alias' | 'qualifier' | 'dot' | 'column' | 'function';

export interface Mark {
  role: Role;
  source?: Source;
}

export interface Analysis {
  tokens: Token[];
  /** Parallel to `tokens`. */
  marks: (Mark | undefined)[];
  /** In order of declaration. */
  sources: Source[];
}

interface Scope {
  parent: Scope | null;
  depth: number;
  hasSelect: boolean;
  sources: Source[];
  selectAliases: Set<string>;
}

/** Per parenthesis level parse state. */
interface Frame {
  depth: number;
  scopeAtOpen: Scope;
  fromMode: boolean;
  expectTable: boolean;
  derived: boolean;
  selectList: boolean;
  deleteList: boolean;
  inWith: boolean;
  cteExpect: boolean;
  intoNext: boolean;
  /** INSERT INTO t ( … ): unqualified names in this frame are t's columns. */
  target?: Source;
  pendingTarget?: {source: Source; at: number};
  /** Index or USING lists: names here are not worth guessing at. */
  noResolve: boolean;
}

interface Ref {
  scope: Scope;
  target?: Source;
  noResolve: boolean;
  /** Token indices of the dotted chain's identifiers (and `*`). */
  parts: number[];
  dots: number[];
  /** A bare name standing for a table, e.g. `DELETE t1 FROM …`. */
  tableRef: boolean;
}

/** Keywords that may sit between FROM/INTO/TABLE and the table name. */
const TABLE_PREFIX = new Set(['IF', 'NOT', 'EXISTS', 'LATERAL', 'IGNORE', 'LOW_PRIORITY', 'QUICK', 'HIGH_PRIORITY', 'ONLY']);

/** Keywords that end a FROM list. */
const CLAUSE_END = new Set([
  'ON', 'USING', 'WHERE', 'GROUP', 'ORDER', 'HAVING', 'LIMIT', 'SET', 'UNION', 'EXCEPT', 'INTERSECT',
  'WINDOW', 'VALUES', 'FOR', 'LOCK', 'OFFSET', 'PARTITION',
]);

const JOINS = new Set(['JOIN', 'STRAIGHT_JOIN']);

const isIdent = (t: Token | undefined): boolean =>
  !!t && (t.kind === 'quoted' || (t.kind === 'word' && !t.keyword));

/** After a dot anything word-shaped is a name: `t.key`, `t.status`. */
const isMember = (t: Token | undefined): boolean =>
  !!t && (t.kind === 'quoted' || t.kind === 'word');

const lower = (t: Token): string => (t.ident ?? t.text).toLowerCase();

export function analyze(sql: string): Analysis {
  const tokens = tokenize(sql);
  const marks: (Mark | undefined)[] = new Array(tokens.length);
  const sources: Source[] = [];
  const refs: Ref[] = [];

  const sig: number[] = [];
  tokens.forEach((t, i) => { if (t.kind !== 'ws' && t.kind !== 'comment') { sig.push(i); } });
  const T = (p: number): Token | undefined => tokens[sig[p]];

  const newScope = (parent: Scope | null, depth: number): Scope =>
    ({parent, depth, hasSelect: false, sources: [], selectAliases: new Set()});
  const newFrame = (depth: number, scopeAtOpen: Scope): Frame => ({
    depth, scopeAtOpen, fromMode: false, expectTable: false, derived: false, selectList: false,
    deleteList: false, inWith: false, cteExpect: false, intoNext: false, noResolve: false,
  });
  const addSource = (src: Omit<Source, 'id'>): Source => {
    const s = {...src, id: sources.length};
    sources.push(s);
    return s;
  };
  const mark = (p: number, role: Role, source?: Source) => { marks[sig[p]] = {role, source}; };

  let cur = newScope(null, 0);
  let stack: Frame[] = [newFrame(0, cur)];
  let ctes = new Map<string, Source>();

  /** Reads `name [AS] alias` at p; returns the source and the last position consumed. */
  const readAlias = (p: number): {alias?: Token; end: number; at?: number} => {
    let q = p;
    if (T(q)?.keyword === 'AS') { q++; }
    if (isIdent(T(q))) { return {alias: T(q), end: q, at: q}; }
    return {end: p - 1};
  };

  for (let p = 0; p < sig.length; p++) {
    const t = T(p)!;
    const f = stack[stack.length - 1];
    if (f.pendingTarget && f.pendingTarget.at !== p) { f.pendingTarget = undefined; }

    if (t.kind === 'semi') {
      cur = newScope(null, 0);
      stack = [newFrame(0, cur)];
      ctes = new Map();
      continue;
    }

    if (t.kind === 'lparen') {
      const nextKw = T(p + 1)?.keyword;
      const prevKw = T(p - 1)?.keyword;
      const fr = newFrame(f.depth + 1, cur);
      if (nextKw === 'SELECT' || nextKw === 'WITH') {
        if (f.expectTable) { fr.derived = true; f.expectTable = false; }
      } else if (f.expectTable) {
        // FROM (a JOIN b ON …): a nested join group
        fr.fromMode = true;
        fr.expectTable = true;
        f.expectTable = false;
      } else if (f.pendingTarget) {
        fr.target = f.pendingTarget.source;
      } else if (prevKw === 'INDEX' || prevKw === 'KEY' || prevKw === 'USING' || f.inWith) {
        fr.noResolve = true;
      } else {
        fr.noResolve = f.noResolve;
      }
      stack.push(fr);
      continue;
    }

    if (t.kind === 'rparen') {
      if (stack.length > 1) {
        const fr = stack.pop()!;
        cur = fr.scopeAtOpen;
        if (fr.derived) {
          const {alias, end, at} = readAlias(p + 1);
          if (alias && at !== undefined) {
            const s = addSource({kind: 'derived', name: alias.ident!, colorKey: lower(alias)});
            cur.sources.push(s);
            mark(at, 'alias', s);
            p = end;
          }
        }
      }
      continue;
    }

    if (t.kind === 'comma') {
      if (f.inWith) { f.cteExpect = true; } else if (f.fromMode) { f.expectTable = true; }
      continue;
    }

    if (t.keyword) {
      const kw = t.keyword;
      if (kw === 'SELECT') {
        if (!cur.hasSelect && cur.sources.length === 0 && cur.depth === f.depth) {
          cur.hasSelect = true;
        } else if (cur.hasSelect && cur.depth === f.depth) {
          cur = newScope(cur.parent, f.depth);   // UNION sibling
          cur.hasSelect = true;
        } else {
          cur = newScope(cur, f.depth);          // INSERT … SELECT, CREATE VIEW … AS SELECT
          cur.hasSelect = true;
        }
        Object.assign(f, {fromMode: false, expectTable: false, inWith: false, cteExpect: false, selectList: true});
      } else if (kw === 'FROM') {
        Object.assign(f, {fromMode: true, expectTable: true, selectList: false, deleteList: false});
      } else if (JOINS.has(kw)) {
        Object.assign(f, {fromMode: true, expectTable: true});
      } else if (kw === 'UPDATE') {
        const onDuplicate = T(p - 1)?.keyword === 'KEY';
        Object.assign(f, {fromMode: !onDuplicate, expectTable: !onDuplicate});
      } else if (kw === 'DELETE') {
        f.deleteList = true;
      } else if (kw === 'INTO') {
        Object.assign(f, {fromMode: false, expectTable: true, intoNext: true, selectList: false});
      } else if (kw === 'TABLE' || kw === 'VIEW') {
        f.expectTable = true;
      } else if (kw === 'WITH') {
        Object.assign(f, {inWith: true, cteExpect: true});
      } else if (kw === 'AS' && f.selectList && isIdent(T(p + 1))) {
        cur.selectAliases.add(lower(T(p + 1)!));
        p++;
      } else if (CLAUSE_END.has(kw)) {
        Object.assign(f, {fromMode: false, expectTable: false, selectList: false, intoNext: false});
      } else if (!TABLE_PREFIX.has(kw) && kw !== 'RECURSIVE') {
        f.expectTable = false;   // FROM DUAL, and anything we don't follow
      }
      continue;
    }

    if (!isIdent(t)) { continue; }

    if (f.cteExpect) {
      const s = addSource({kind: 'cte', name: t.ident!, colorKey: lower(t)});
      ctes.set(lower(t), s);
      mark(p, 'table', s);
      f.cteExpect = false;
      continue;
    }

    // a dotted chain: a, a.b, a.b.c, a.*
    const parts = [p];
    const dots: number[] = [];
    let q = p;
    while (T(q + 1)?.kind === 'dot' && (isMember(T(q + 2)) || T(q + 2)?.text === '*')) {
      dots.push(q + 1);
      parts.push(q + 2);
      q += 2;
    }

    if (f.expectTable) {
      const nameTok = T(q)!;
      const schemaPos = parts.length > 1 ? parts[parts.length - 2] : undefined;
      const {alias, end, at} = readAlias(q + 1);
      const cte = parts.length === 1 ? ctes.get(lower(nameTok)) : undefined;
      let s: Source;
      if (cte && !alias) {
        s = cte;
      } else {
        s = addSource({
          kind: cte ? 'cte' : 'table',
          name: nameTok.ident!,
          schema: schemaPos !== undefined ? T(schemaPos)!.ident : undefined,
          alias: alias?.ident,
          colorKey: lower(nameTok),
        });
      }
      cur.sources.push(s);
      if (schemaPos !== undefined) { mark(schemaPos, 'schema'); }
      mark(q, 'table', s);
      if (at !== undefined) { mark(at, 'alias', s); }
      f.expectTable = false;
      const last = Math.max(q, end);
      if (f.intoNext) {
        f.intoNext = false;
        f.pendingTarget = {source: s, at: last + 1};
      }
      p = last;
      continue;
    }

    if (parts.length === 1 && T(q + 1)?.kind === 'lparen') {
      mark(p, 'function');
      continue;
    }

    // `SELECT a.id x`: an alias without AS follows an expression directly
    const prev = T(p - 1);
    if (f.selectList && parts.length === 1 && prev &&
        (isIdent(prev) || ['rparen', 'number', 'string', 'placeholder'].includes(prev.kind))) {
      cur.selectAliases.add(lower(t));
      p = q;
      continue;
    }

    refs.push({scope: cur, target: f.target, noResolve: f.noResolve, parts, dots, tableRef: f.deleteList});
    p = q;
  }

  // Pass 2: every scope's FROM is known now, so qualifiers can be resolved.
  for (const ref of refs) {
    const name = (pos: number) => lower(T(pos)!);
    if (ref.parts.length >= 2) {
      const qual = ref.parts[ref.parts.length - 2];
      const s = lookup(ref.scope, name(qual));
      if (!s) { continue; }
      if (ref.parts.length >= 3) { mark(ref.parts[ref.parts.length - 3], 'schema'); }
      mark(qual, 'qualifier', s);
      mark(ref.dots[ref.dots.length - 1], 'dot', s);
      mark(ref.parts[ref.parts.length - 1], 'column', s);
    } else if (ref.tableRef) {
      const s = lookup(ref.scope, name(ref.parts[0]));
      if (s) { mark(ref.parts[0], 'qualifier', s); }
    } else if (ref.target) {
      mark(ref.parts[0], 'column', ref.target);
    } else if (!ref.noResolve && ref.scope.sources.length === 1) {
      const n = name(ref.parts[0]);
      const only = ref.scope.sources[0];
      if (!ref.scope.selectAliases.has(n) && n !== only.alias?.toLowerCase()) {
        mark(ref.parts[0], 'column', only);
      }
    }
  }

  return {tokens, marks, sources};
}

/** Innermost scope first; an alias wins over a bare table name. */
function lookup(scope: Scope | null, name: string): Source | undefined {
  for (let s = scope; s; s = s.parent) {
    const hit = s.sources.find(x => x.alias?.toLowerCase() === name)
      ?? s.sources.find(x => !x.alias && x.name.toLowerCase() === name)
      ?? s.sources.find(x => x.name.toLowerCase() === name);
    if (hit) { return hit; }
  }
  return undefined;
}
