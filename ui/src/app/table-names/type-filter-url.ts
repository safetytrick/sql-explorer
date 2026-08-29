import { TypeFilter } from '../schema-tree/tree-filter';

/**
 * The URL spelling of the `/tables` all/tables/views control.
 *
 * `var-` prefixed because this is presentation owned by one page, which is
 * what that prefix is for — it keeps the name from colliding with the
 * cross-cutting search vocabulary (`tags`, `tagMode`, `tables`, `columns`)
 * that several pages share. `tables` in particular is already taken, and
 * means something else entirely: a scope of table keys.
 */
export const TYPE_PARAM = 'var-type';

const VALUES: readonly TypeFilter[] = ['all', 'tables', 'views'];

/**
 * Decodes the param, falling back to the default for anything unrecognised.
 * A hand-edited or truncated URL should show every row rather than nothing —
 * silently filtering everything out on a typo is the worse failure, because
 * it looks like the schema is empty.
 */
export function readTypeFilter(raw: string | null | undefined): TypeFilter {
  return VALUES.includes(raw as TypeFilter) ? raw as TypeFilter : 'all';
}
