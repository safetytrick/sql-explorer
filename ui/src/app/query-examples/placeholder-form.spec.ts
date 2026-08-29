import {
  buildStatement, columnBefore, insideInList, isComplete, kindOf, quote, readPlaceholders, toLiteral
} from './placeholder-form';

describe('placeholder-form', () => {

  const COLUMNS = [
    {name: 'id', type: 'BIGINT'},
    {name: 'total', type: 'INT'},
    {name: 'name', type: 'VARCHAR'},
    {name: 'created_on', type: 'DATETIME'},
    {name: 'due', type: 'DATE'}
  ];

  describe('reading placeholders off the statement', () => {

    it('finds one per question mark', () => {
      const found = readPlaceholders('select id from t where total > ? and name = ?', COLUMNS);

      expect(found.length).toBe(2);
      expect(found.map(p => p.column)).toEqual(['total', 'name']);
    });

    /** The schema types the input; without a match it stays free text. */
    it('types each one from the column it is compared against', () => {
      const found = readPlaceholders(
        'select id from t where total > ? and name = ? and created_on > ? and due = ?', COLUMNS);

      expect(found.map(p => p.kind)).toEqual(['number', 'text', 'datetime', 'date']);
    });

    it('falls back to text when the column is unknown', () => {
      const [only] = readPlaceholders('select id from t where mystery = ?', COLUMNS);

      expect(only.column).toBe('mystery');
      expect(only.columnType).toBeNull();
      expect(only.kind).toBe('text');
    });

    /**
     * A placeholder that resolves to no column at all still has to be
     * fillable — refusing to render the form would be worse than an untyped
     * input.
     */
    it('still offers an input when no column can be found', () => {
      const [only] = readPlaceholders('select ?', COLUMNS);

      expect(only.column).toBeNull();
      expect(only.kind).toBe('text');
    });

    it('reads backtick-quoted columns', () => {
      const [only] = readPlaceholders('select a from t where `total` > ?', COLUMNS);

      expect(only.column).toBe('total');
      expect(only.kind).toBe('number');
    });
  });

  describe('IN lists', () => {

    /**
     * The corpus renders every IN as `IN (?)` because repeat collapsing reduces
     * it, so the stored text cannot say how many elements the real statement
     * had. The form is where that is chosen.
     */
    it('recognises a placeholder inside an IN list', () => {
      const [only] = readPlaceholders('select a from t where id in (?)', COLUMNS);

      expect(only.kind).toBe('list');
    });

    it('does not mistake an ordinary parenthesis for a list', () => {
      const [only] = readPlaceholders('select a from t where (total > ?)', COLUMNS);

      expect(only.kind).toBe('number');
    });

    it('expands to as many elements as were entered', () => {
      const found = readPlaceholders('select a from t where id in (?)', COLUMNS);
      found[0].values = ['1', '2', '3'];

      expect(buildStatement('select a from t where id in (?)', found))
        .toBe('select a from t where id in (1, 2, 3)');
    });

    it('ignores blank rows in the list', () => {
      const found = readPlaceholders('select a from t where id in (?)', COLUMNS);
      found[0].values = ['1', '  ', '2'];

      expect(buildStatement('select a from t where id in (?)', found))
        .toBe('select a from t where id in (1, 2)');
    });
  });

  describe('building the statement back', () => {

    it('substitutes every placeholder', () => {
      const sql = 'select id from t where total > ? and name = ?';
      const found = readPlaceholders(sql, COLUMNS);
      found[0].value = '100';
      found[1].value = 'bob';

      expect(buildStatement(sql, found)).toBe('select id from t where total > 100 and name = \'bob\'');
    });

    /**
     * Substitution runs right-to-left because offsets were recorded against the
     * original string; going left-to-right would shift every offset not yet
     * used as soon as a literal is longer than the `?` it replaced.
     */
    it('is not confused by a replacement longer than the placeholder', () => {
      const sql = 'select a from t where x = ? and y = ?';
      const found = readPlaceholders(sql, COLUMNS);
      found[0].value = 'a-very-long-value-indeed';
      found[1].value = 'short';

      expect(buildStatement(sql, found))
        .toBe('select a from t where x = \'a-very-long-value-indeed\' and y = \'short\'');
    });

    it('writes NULL for an empty value rather than an empty string', () => {
      const sql = 'select a from t where x = ?';
      const found = readPlaceholders(sql, COLUMNS);

      expect(buildStatement(sql, found)).toBe('select a from t where x = NULL');
    });
  });

  describe('escaping', () => {

    /**
     * Ergonomics rather than a safety boundary: the statement is parsed by the
     * endpoint to derive a digest, never executed, so a bad escape is a parse
     * error or a different digest — both loud.
     */
    it('doubles embedded quotes', () => {
      expect(quote('it\'s')).toBe('\'it\'\'s\'');
      expect(quote('a\'b\'c')).toBe('\'a\'\'b\'\'c\'');
    });

    it('leaves a well-formed number unquoted and quotes anything else', () => {
      const numeric = {kind: 'number'} as never;
      expect(toLiteral(numeric, '42')).toBe('42');
      expect(toLiteral(numeric, '-1.5')).toBe('-1.5');
      expect(toLiteral(numeric, 'not a number')).toBe('\'not a number\'');
    });
  });

  describe('completeness', () => {

    it('is incomplete until every placeholder has a value', () => {
      const found = readPlaceholders('select a from t where x = ? and y = ?', COLUMNS);
      expect(isComplete(found)).toBeFalse();

      found[0].value = '1';
      expect(isComplete(found)).toBeFalse();

      found[1].value = '2';
      expect(isComplete(found)).toBeTrue();
    });

    it('counts a list as filled once any element is', () => {
      const found = readPlaceholders('select a from t where id in (?)', COLUMNS);
      expect(isComplete(found)).toBeFalse();

      found[0].values = ['', '7'];
      expect(isComplete(found)).toBeTrue();
    });
  });

  describe('helpers', () => {
    it('maps SQL types to inputs', () => {
      expect(kindOf('BIGINT')).toBe('number');
      expect(kindOf('DECIMAL')).toBe('number');
      expect(kindOf('DATE')).toBe('date');
      expect(kindOf('TIMESTAMP')).toBe('datetime');
      expect(kindOf('TIME')).toBe('time');
      expect(kindOf('VARCHAR')).toBe('text');
      expect(kindOf(null)).toBe('text');
    });

    it('locates the column and the IN context independently', () => {
      expect(columnBefore('where total > ?', 14)).toBe('total');
      expect(insideInList('where id in (?)', 13)).toBeTrue();
      expect(insideInList('where id = ?', 11)).toBeFalse();
    });
  });
});
