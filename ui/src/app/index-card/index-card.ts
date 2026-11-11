import {IndexUsage} from '../schema-lens.service';

/**
 * Indexes ordered by their columns, compared column by column, so indexes
 * sharing a leading column sit together and one that is a prefix of another
 * — usually redundant — sits directly above it. The primary key stays first,
 * and hinted indexes the table does not have go last.
 */
export function orderIndexesByColumns(indexes: IndexUsage[]): IndexUsage[] {
  const rank = (index: IndexUsage) => !index.exists ? 2 : index.primary ? 0 : 1;
  return [...indexes].sort((a, b) => rank(a) - rank(b)
    || compareColumns(a.columns, b.columns)
    || a.name.localeCompare(b.name));
}

function compareColumns(a: string[], b: string[]): number {
  for (let i = 0; i < Math.min(a.length, b.length); i++) {
    const order = a[i].toLowerCase().localeCompare(b[i].toLowerCase());
    if (order) { return order; }
  }
  return a.length - b.length;
}
