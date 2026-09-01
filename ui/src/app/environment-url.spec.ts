import {convertToParamMap} from '@angular/router';
import {ENV_PARAM, envFromParams, envFromSearch, withEnv} from './environment-url';

/**
 * Which corpus a URL is about.
 *
 * <p>The parameter exists because every other URL in the app is relative to an
 * environment it does not name, and two of this deployment's environments share
 * four schema names — so a link to a table in any of them resolves against
 * whatever the recipient last picked (sqlx-29f).
 */
describe('environment-url', () => {

  it('reads the environment a link names', () => {
    expect(envFromSearch('?env=regression')).toBe('regression');
    expect(envFromParams(convertToParamMap({[ENV_PARAM]: 'local'}))).toBe('local');
  });

  it('reads it alongside other parameters', () => {
    expect(envFromSearch('?tags=system&env=local&tagMode=ALL')).toBe('local');
  });

  /** A URL that names none is not a URL naming an environment called "". */
  it('treats an absent or blank value as no environment', () => {
    expect(envFromSearch('')).toBeNull();
    expect(envFromSearch('?tags=system')).toBeNull();
    expect(envFromSearch('?env=')).toBeNull();
    expect(envFromSearch('?env=%20%20')).toBeNull();
    expect(envFromParams(convertToParamMap({}))).toBeNull();
  });

  /**
   * Read during service construction, before anything has validated the URL.
   * Throwing here would stop the application from starting over a malformed
   * address — a much worse failure than ignoring the parameter.
   */
  it('never throws on a malformed query string', () => {
    expect(() => envFromSearch('?%%%')).not.toThrow();
    expect(() => envFromSearch('not a query string at all')).not.toThrow();
  });

  it('survives a name that needs encoding', () => {
    expect(envFromSearch('?env=' + encodeURIComponent('prod eu-west'))).toBe('prod eu-west');
  });
});

/**
 * Switching environments has to change the address in the same navigation that
 * reloads the route. Stamping afterwards loses a race: the URL to return to is
 * captured first, so the app would navigate back to the old environment's
 * address and only then correct it.
 */
describe('withEnv', () => {

  it('adds the environment to an address that has none', () => {
    expect(withEnv('/tables', 'local')).toBe('/tables?env=local');
  });

  it('replaces one that is already there', () => {
    expect(withEnv('/tables?env=regression', 'local')).toBe('/tables?env=local');
  });

  it('keeps the parameters already on the address', () => {
    const url = withEnv('/unqueried?schemaTagsExclude=system&env=regression', 'local');
    expect(url).toContain('schemaTagsExclude=system');
    expect(url).toContain('env=local');
    expect(url).not.toContain('env=regression');
  });

  it('encodes a name that needs it', () => {
    expect(withEnv('/tables', 'prod eu-west')).toContain('env=prod+eu-west');
  });
});
