import {generatorOrigin, originLabel, originRules} from './generator-origin';

describe('generator origin', () => {
  const walker = {generator: 'doctrine-orm', rule: 'dql-walker-alias'};
  const insertD = {generator: 'doctrine-orm', rule: 'uow-insert'};
  const insertH = {generator: 'hibernate', rule: 'uow-insert'};

  it('is only a candidate until the environment confirms the generator', () => {
    const origin = generatorOrigin([walker], new Map());
    expect(origin.kind).toBe('candidate');
    expect(originLabel(origin)).toBe('candidate: doctrine-orm');
  });

  it('becomes the asserted origin once confirmed, without re-analysis', () => {
    const origin = generatorOrigin([walker], new Map([['doctrine-orm', 'used' as const]]));
    expect(origin.kind).toBe('asserted');
    expect(originLabel(origin)).toBe('doctrine-orm');
  });

  it('shows every candidate when several match and none is confirmed', () => {
    expect(originLabel(generatorOrigin([insertD, insertH], new Map()))).toBe('candidates: doctrine-orm, hibernate');
  });

  it('asserts the one confirmed generator of a shared shape', () => {
    const origin = generatorOrigin([insertD, insertH], new Map([['hibernate', 'used' as const]]));
    expect(origin.kind).toBe('asserted');
    expect(origin.generators).toEqual(['hibernate']);
  });

  it('is ambiguous when two confirmed generators both fit', () => {
    const origin = generatorOrigin([insertD, insertH],
      new Map([['hibernate', 'used' as const], ['doctrine-orm', 'used' as const]]));
    expect(origin.kind).toBe('ambiguous');
    expect(originLabel(origin)).toBe('doctrine-orm or hibernate');
  });

  it('drops a generator a person said this environment does not use', () => {
    expect(generatorOrigin([walker], new Map([['doctrine-orm', 'not-used' as const]])).kind).toBe('none');
  });

  it('names the matching rules for hover text', () => {
    expect(originRules(generatorOrigin([walker, insertD], new Map())))
      .toBe('doctrine-orm: dql-walker-alias, uow-insert');
  });

  it('is nothing for nothing', () => {
    expect(generatorOrigin(null, new Map()).kind).toBe('none');
    expect(originLabel(generatorOrigin([], new Map()))).toBe('');
  });
});
