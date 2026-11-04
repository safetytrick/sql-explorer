import {decodeTableSearch, encodeTableSearch, parseColumns} from './table-search-url';

function reader(params: Record<string, string>) {
  return { get: (name: string) => params[name] ?? null };
}

describe('the /tables search URL', () => {

  it('parses typed columns on commas or whitespace, lower-cased, once each', () => {
    expect(parseColumns('entity_id, Entity_Type  entity_id')).toEqual(['entity_id', 'entity_type']);
    expect(parseColumns('  ')).toEqual([]);
  });

  it('round-trips a search', () => {
    const state = { name: 'invoice_*', columns: ['entity_id', 'entity_type'], exclude: ['app.notes'] };

    const encoded = encodeTableSearch(state);
    expect(encoded).toEqual({
      'var-name': 'invoice_*', 'var-columns': 'entity_id,entity_type', 'var-exclude': 'app.notes'
    });
    expect(decodeTableSearch(reader(encoded as Record<string, string>))).toEqual(state);
  });

  it('clears defaults out of the URL rather than writing them', () => {
    expect(encodeTableSearch({ name: '  ', columns: [], exclude: [] }))
      .toEqual({ 'var-name': null, 'var-columns': null, 'var-exclude': null });
    expect(decodeTableSearch(reader({}))).toEqual({ name: '', columns: [], exclude: [] });
  });
});
