/**
 * The ERD layout's cards and line ends (sqlx-929): the grid layout with each
 * table drawn as an entity listing its key columns, lines meeting the rows of
 * the columns they relate, and each end marked one or many.
 *
 * <p>A card lists the primary key, the foreign keys and the columns a drawn
 * relationship attaches to, then counts the rest as "+ N more". Listing every
 * column would make a wide table many times taller than its neighbours, and a
 * grid row is as tall as its tallest cell, so the rest of the row would be
 * empty space.
 *
 * <p>THE KIND OF A RELATIONSHIP IS INFERRED, NOT DECLARED. Real schemas often
 * carry no foreign keys, and the joins are what show how the design is used,
 * so an end is "one" when a unique index covers the columns joined on that
 * side, and "many" when none does. That is a statement about the schema, not
 * about the data: a column with no unique index may still never repeat.
 *
 * <p>Pure: sizes and kinds only. Drawing lives in the component.
 */
import {GraphColumnPair, GraphKeyColumn} from '../schema-lens.service';

/** Every card is one width, so a grid column is never widened by one long name. */
export const ERD_WIDTH = 190;
export const ERD_HEADER = 24;
export const ERD_ROW = 16;
/** Space under the last row. */
export const ERD_FOOT = 4;

/** A card's height: its header, a row per key column, and one more for "+ N more". */
export function cardHeight(columns: number, more: number): number {
  return ERD_HEADER + (columns + (more > 0 ? 1 : 0)) * ERD_ROW + ERD_FOOT;
}

/** How many columns a card does not list; none when the table's structure is unknown. */
export function unlisted(columnCount: number | null, columns: GraphKeyColumn[]): number {
  return columnCount === null ? 0 : Math.max(0, columnCount - columns.length);
}

/**
 * Where a line meets a card, as an offset from the card's top: the middle of
 * the column's row, or of the header when the card does not list the column.
 */
export function portOffset(columns: GraphKeyColumn[], column: string | undefined): number {
  const index = column === undefined ? -1
    : columns.findIndex(candidate => candidate.name.toLowerCase() === column.toLowerCase());
  return index < 0 ? ERD_HEADER / 2 : ERD_HEADER + index * ERD_ROW + ERD_ROW / 2;
}

/** The index of the row at an offset from the card's top, or -1 on the header or below the rows. */
export function rowAt(columns: GraphKeyColumn[], offset: number): number {
  const index = Math.floor((offset - ERD_HEADER) / ERD_ROW);
  return offset >= ERD_HEADER && index < columns.length ? index : -1;
}

/**
 * One end of a relationship: how many rows on this side match one row on the
 * other, and whether a row on the other side can have none here.
 */
export interface End {
  many: boolean;
  /** Drawn as a circle: the other side's column is nullable, so it may point at nothing. */
  optional: boolean;
}

/**
 * The ends of a column pair, or null when either table's structure is not
 * known and nothing can be inferred.
 *
 * <ul>
 *   <li>unique on one side only: that side is the one, the other the many;</li>
 *   <li>unique on both: one to one;</li>
 *   <li>unique on neither: many to many, which for a join usually means the
 *       tables meet through a column they both borrow from a third.</li>
 * </ul>
 */
export function endsOf(pair: GraphColumnPair): {a: End; b: End} | null {
  if (pair.aUnique === null || pair.aUnique === undefined || pair.bUnique === null || pair.bUnique === undefined) {
    return null;
  }
  return {
    a: {many: !pair.aUnique, optional: pair.aUnique && pair.bNullable === true},
    b: {many: !pair.bUnique, optional: pair.bUnique && pair.aNullable === true}
  };
}

/** The kind in words, a side first: "many to one", "one to one", "many to many". */
export function kindOf(pair: GraphColumnPair): string | null {
  const ends = endsOf(pair);
  if (!ends) { return null; }
  const word = (end: End) => end.many ? 'many' : 'one';
  return `${word(ends.a)} to ${word(ends.b)}`;
}
