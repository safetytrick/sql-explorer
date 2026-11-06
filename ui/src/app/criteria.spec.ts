import {addCriterion, criteriaOf, effectiveState, negate, removeCriterion, toggleOff} from './criteria';
import {defaultSearchState, SearchUrlState} from './search-url';

/** The pills of a search, and what switching, flipping and removing one does (sqlx-fph). */
describe('criteria', () => {

  function search(patch: (state: SearchUrlState) => void): SearchUrlState {
    const state = defaultSearchState();
    patch(state);
    return state;
  }

  const summary = (state: SearchUrlState) =>
    criteriaOf(state).map(c => `${c.exclude ? '≠' : ''}${c.label}:${c.value}${c.on ? '' : ' (off)'}`);

  it('lists every criterion, scope first, each with whether it can be flipped', () => {
    const state = search(s => {
      s.scope.tables = ['app_main.account'];
      s.scope.excludeSchemas = ['app_audit'];
      s.types = ['SELECT'];
      s.excludeTags = ['legacy'];
      s.fullScan = 'YES';
      s.avgTime = {min: 10, max: null};
      s.excludeQueries = ['v3:aaa'];
    });
    expect(summary(state)).toEqual([
      '≠schema:app_audit',
      'table:app_main.account',
      'type:SELECT',
      '≠tag:legacy',
      '≠query:v3:aaa',
      'avg time:≥ 10 ms',
      'full scan:yes'
    ]);
    const negatable = Object.fromEntries(criteriaOf(state).map(c => [c.key, c.negatable]));
    expect(negatable['tag:legacy']).toBeTrue();
    expect(negatable['query:v3:aaa']).toBeFalse();
    expect(negatable['fullScan']).toBeFalse();
    expect(negatable['avgTime']).toBeFalse();
  });

  it('switches a criterion off and back on, keeping its value', () => {
    const state = search(s => { s.tags = ['billing']; s.fullScan = 'YES'; });
    const off = toggleOff(state, 'tag:billing');
    expect(off.off).toEqual(['tag:billing']);
    expect(off.tags).toEqual(['billing']);
    expect(summary(off)).toContain('tag:billing (off)');
    expect(toggleOff(off, 'tag:billing').off).toEqual([]);
    // the original is untouched
    expect(state.off).toEqual([]);
  });

  it('flips include and exclude, and a switched-off criterion stays off when flipped', () => {
    const state = toggleOff(search(s => { s.scope.tables = ['app_main.site']; }), 'table:app_main.site');
    const flipped = negate(state, 'table:app_main.site');
    expect(flipped.scope.tables).toEqual([]);
    expect(flipped.scope.excludeTables).toEqual(['app_main.site']);
    expect(flipped.off).toEqual(['table:app_main.site']);
    expect(negate(flipped, 'table:app_main.site').scope.tables).toEqual(['app_main.site']);
    // nothing to flip on a criterion that has only one side
    expect(negate(state, 'fullScan')).toBe(state);
  });

  it('removes a criterion, and its paused state with it', () => {
    const state = toggleOff(search(s => { s.avgTime = {min: 10, max: 50}; s.excludeTypes = ['INSERT']; }), 'avgTime');
    const removed = removeCriterion(removeCriterion(state, 'avgTime'), 'type:INSERT');
    expect(removed.avgTime).toEqual({min: null, max: null});
    expect(removed.excludeTypes).toEqual([]);
    expect(removed.off).toEqual([]);
  });

  it('adds a value on the side asked for, moving it from the other side and switching it on', () => {
    let state = search(s => { s.tags = ['legacy']; s.off = ['tag:legacy']; });
    state = addCriterion(state, 'tag', 'legacy', true);
    expect(state.tags).toEqual([]);
    expect(state.excludeTags).toEqual(['legacy']);
    expect(state.off).toEqual([]);
    expect(addCriterion(state, 'query', 'v3:aaa', true).excludeQueries).toEqual(['v3:aaa']);
    // a query has no include side, so nothing is added there
    expect(addCriterion(state, 'query', 'v3:aaa', false)).toBe(state);
  });

  it('runs the search without what is switched off', () => {
    const state = search(s => {
      s.queryRegex = 'invoice';
      s.scope.excludeTables = ['app_main.note'];
      s.schemaTagFilter.includeTags = ['billing'];
      s.off = ['q', 'table:app_main.note', 'schemaTag:billing'];
    });
    const running = effectiveState(state);
    expect(running.queryRegex).toBe('');
    expect(running.scope.excludeTables).toEqual([]);
    expect(running.schemaTagFilter.includeTags).toEqual([]);
    expect(running.off).toEqual([]);
    expect(effectiveState(defaultSearchState())).toEqual(defaultSearchState());
  });
});
