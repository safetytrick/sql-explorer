import {ExplainRow} from './explain-parser';

/**
 * What a plan says, in words (sqlx-2ko.8): the handful of things worth
 * noticing in an EXPLAIN, read from the rows the parser already produced.
 *
 * Each finding names the table and the evidence, so it can be checked against
 * the table above rather than taken on trust. Ordered worst first.
 */
export interface ExplainFinding {
  severity: 'bad' | 'warn' | 'note';
  table: string | null;
  text: string;
}

/** Below this many estimated rows a scan is cheap enough not to mention. */
const SMALL = 100;

export function explainFindings(rows: ExplainRow[]): ExplainFinding[] {
  const found: ExplainFinding[] = [];
  for (const row of rows) {
    const table = row.table;
    const estimate = row.rows ?? null;
    const many = estimate === null || estimate >= SMALL;
    const extra = (row.extra ?? '').toLowerCase();
    const scale = estimate === null ? '' : ` (about ${estimate.toLocaleString('en-US')} rows)`;
    const type = (row.type ?? '').toUpperCase();

    if (type === 'ALL' && many) {
      found.push({severity: 'bad', table,
        text: row.possibleKeys.length
          ? `full table scan${scale}, although ${row.possibleKeys.join(', ')} could have been used: the optimizer judged a scan cheaper`
          : `full table scan${scale}: no index fits how it is filtered or joined`});
    } else if (type === 'INDEX' && many) {
      found.push({severity: 'warn', table,
        text: `full index scan of ${row.key ?? 'an index'}${scale}: every entry is read, which beats the table only by being narrower`});
    } else if (!row.key && row.possibleKeys.length && type !== 'ALL') {
      found.push({severity: 'warn', table, text: `${row.possibleKeys.join(', ')} could have been used, but no index was chosen`});
    }

    if (row.filtered !== null && row.filtered < 10 && many && type !== 'CONST' && type !== 'EQ_REF') {
      found.push({severity: 'warn', table,
        text: `only ${row.filtered}% of the rows read survive the remaining conditions: most of what is read is discarded`});
    }
    if (extra.includes('using temporary')) {
      found.push({severity: 'warn', table, text: 'builds a temporary table (GROUP BY, DISTINCT, UNION or ORDER BY on another table)'});
    }
    if (extra.includes('using filesort')) {
      found.push({severity: 'warn', table, text: 'sorts rows itself (filesort): no index delivers them in the order asked for'});
    }
    if (extra.includes('using join buffer')) {
      found.push({severity: 'bad', table, text: 'joined through a join buffer: no index on the join column, so rows are matched in bulk'});
    }
    if ((row.selectType ?? '').toUpperCase().includes('DEPENDENT')) {
      found.push({severity: 'bad', table,
        text: `${(row.selectType ?? '').toLowerCase()}: re-run for every row of the outer query`});
    }
    if (extra.includes('using index') && !extra.includes('using index condition') && !extra.includes('using index for')) {
      found.push({severity: 'note', table, text: `covered by ${row.key ?? 'the index'}: answered from the index alone, without reading rows`});
    }
  }
  const rank = {bad: 0, warn: 1, note: 2};
  return found.sort((a, b) => rank[a.severity] - rank[b.severity]);
}
