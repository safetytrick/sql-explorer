import {GraphColumnPair, GraphKeyColumn} from '../schema-lens.service';
import {cardHeight, endsOf, ERD_HEADER, ERD_ROW, kindOf, portOffset, rowAt, unlisted} from './erd';

const column = (name: string): GraphKeyColumn =>
  ({ name, type: 'BIGINT', nullable: false, primary: false, unique: false, foreignKey: false });

const pair = (over: Partial<GraphColumnPair>): GraphColumnPair =>
  ({ a: 'account_id', b: 'id', aUnique: false, bUnique: true, aNullable: false, bNullable: false, declared: null, ...over });

describe('erd', () => {

  it('sizes a card by its key columns, with a row for the rest only when there are any', () => {
    expect(cardHeight(3, 0)).toBe(cardHeight(2, 5));
    expect(cardHeight(2, 5) - cardHeight(2, 0)).toBe(ERD_ROW);
    expect(unlisted(12, [column('id'), column('account_id')])).toBe(10);
    // a table whose structure was never read has nothing more to count
    expect(unlisted(null, [column('session_id')])).toBe(0);
  });

  it('puts a line at the middle of its column\'s row, whatever the case, and on the header for no column', () => {
    const columns = [column('id'), column('account_id')];
    expect(portOffset(columns, 'ACCOUNT_ID')).toBe(ERD_HEADER + ERD_ROW * 1.5);
    expect(portOffset(columns, undefined)).toBe(ERD_HEADER / 2);
    expect(portOffset(columns, 'not_listed')).toBe(ERD_HEADER / 2);
    expect(rowAt(columns, portOffset(columns, 'account_id'))).toBe(1);
    expect(rowAt(columns, 4)).toBe(-1);
    expect(rowAt(columns, ERD_HEADER + ERD_ROW * 2.5)).toBe(-1);
  });

  it('reads the kind off which ends are unique, with no declared key', () => {
    expect(kindOf(pair({}))).toBe('many to one');
    expect(kindOf(pair({ aUnique: true }))).toBe('one to one');
    expect(kindOf(pair({ bUnique: false }))).toBe('many to many');
  });

  it('marks the one end optional when the column pointing at it is nullable', () => {
    expect(endsOf(pair({ aNullable: true }))).toEqual({ a: { many: true, optional: false }, b: { many: false, optional: true } });
    expect(endsOf(pair({}))!.b.optional).toBeFalse();
  });

  it('infers nothing when a table\'s structure is unknown', () => {
    expect(endsOf(pair({ aUnique: null }))).toBeNull();
    expect(kindOf(pair({ bUnique: null }))).toBeNull();
  });
});
