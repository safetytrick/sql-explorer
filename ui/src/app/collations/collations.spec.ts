import {
  decodeEncodingFilter, encodeEncodingFilter, encodingChoices, groupByEncoding, joinMatchesFilter,
  JoinEncodingMismatch, matchesEncodingFilter, NO_ENCODING_FILTER, TableEncoding
} from './collations';

const U = {characterSet: 'utf8mb4', collation: 'utf8mb4_0900_ai_ci'};
const B = {characterSet: 'utf8mb4', collation: 'utf8mb4_bin'};
const L = {characterSet: 'utf8mb3', collation: 'utf8mb3_general_ci'};

function table(name: string, ...encodings: {characterSet: string; collation: string}[]): TableEncoding {
  const charsets = new Set(encodings.map(e => e.characterSet));
  const collations = new Set(encodings.map(e => e.collation));
  return {
    schema: 'app_main', table: name, view: false, textColumns: encodings.length,
    predominantCharset: encodings[0]?.characterSet ?? null, predominantCollation: encodings[0]?.collation ?? null,
    mixedCharsets: charsets.size > 1, mixedCollations: collations.size > 1,
    encodings: encodings.map((e, i) => ({...e, columns: [`c${i}`]})), outliers: []
  };
}

function params(values: Record<string, string>) {
  return {get: (name: string) => values[name] ?? null};
}

describe('collations', () => {
  const account = table('account', U, B);
  const site = table('site', L);
  const accountSite = table('account_site');
  const tag = table('tag', U);
  const note = table('note', U);

  it('groups tables by the exact set of encodings they use, largest first, skipping tables with none', () => {
    const groups = groupByEncoding([account, site, accountSite, tag, note]);

    // ties fall back to the encodings' names, so the order is stable
    expect(groups.map(group => group.tables.map(t => t.table))).toEqual([['tag', 'note'], ['site'], ['account']]);
    expect(groups[2].mixed).toBeTrue();
    expect(groups[2].encodings.map(e => e.collation)).toEqual(['utf8mb4_0900_ai_ci', 'utf8mb4_bin']);
  });

  it('matches a charset or collation the table uses anywhere, not only its predominant one', () => {
    expect(matchesEncodingFilter(account, {...NO_ENCODING_FILTER, collation: 'utf8mb4_bin'})).toBeTrue();
    expect(matchesEncodingFilter(site, {...NO_ENCODING_FILTER, charset: 'utf8mb4'})).toBeFalse();
    expect(matchesEncodingFilter(site, {...NO_ENCODING_FILTER, mixedOnly: true})).toBeFalse();
    expect(matchesEncodingFilter(account, {...NO_ENCODING_FILTER, name: '/^acc/'})).toBeTrue();
  });

  it('keeps a join when either side passes', () => {
    const join: JoinEncodingMismatch = {
      left: {schema: 'app_main', table: 'account', column: 'code', ...U},
      right: {schema: 'app_main', table: 'site', column: 'account_code', ...L},
      charsetDiffers: true, queries: 2, executions: 10, exampleDigest: 'd1'
    };
    expect(joinMatchesFilter(join, {...NO_ENCODING_FILTER, charset: 'utf8mb3'})).toBeTrue();
    expect(joinMatchesFilter(join, {...NO_ENCODING_FILTER, name: 'tag'})).toBeFalse();
  });

  it('round-trips its filters through the URL and omits the defaults', () => {
    const filter = {name: 'acc', charset: 'utf8mb3', collation: null, mixedOnly: true};
    const encoded = encodeEncodingFilter(filter);

    expect(encoded).toEqual({'var-name': 'acc', 'var-charset': 'utf8mb3', 'var-collation': null, 'var-flag': 'mixed'});
    expect(decodeEncodingFilter(params(encoded as Record<string, string>))).toEqual(filter);
    expect(Object.values(encodeEncodingFilter(NO_ENCODING_FILTER)).every(value => value === null)).toBeTrue();
  });

  it('reads an unknown flag as the default rather than an empty page', () => {
    expect(decodeEncodingFilter(params({'var-flag': 'bogus'})).mixedOnly).toBeFalse();
  });

  it('offers every charset and collation in use as a choice', () => {
    expect(encodingChoices([account, site])).toEqual({
      charsets: ['utf8mb3', 'utf8mb4'],
      collations: ['utf8mb3_general_ci', 'utf8mb4_0900_ai_ci', 'utf8mb4_bin']
    });
  });
});
