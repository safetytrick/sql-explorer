import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {EnvironmentService, EnvironmentList} from './environment.service';
import {INITIAL_PATH, INITIAL_SEARCH} from './environment-url';

/**
 * The environment is which corpus a page is about, so getting it wrong does not
 * produce an error — it produces real numbers answering a different question
 * (sqlx-29f).
 */
describe('EnvironmentService', () => {

  const list = (...names: string[]): EnvironmentList => ({
    defaultEnvironment: names[0],
    environments: names.map(name => ({
      name,
      servers: [],
      sources: {loaded: true, sourceCount: 1, loadedOn: null, importStats: null}
    }))
  });

  function make(search: string, stored: string | null, path = '/') {
    localStorage.clear();
    if (stored) {
      localStorage.setItem('sql-explorer.environment', stored);
    }
    // The query string is read at construction, before the router has resolved
    // anything — that timing is what makes a shared link's FIRST request go to
    // the right environment, and it is why the value is injected.
    TestBed.configureTestingModule({
      providers: [
        EnvironmentService, provideHttpClient(), provideHttpClientTesting(),
        {provide: INITIAL_SEARCH, useValue: search},
        {provide: INITIAL_PATH, useValue: path}
      ]
    });
    return {
      service: TestBed.inject(EnvironmentService),
      http: TestBed.inject(HttpTestingController)
    };
  }

  afterEach(() => localStorage.clear());

  /** The whole point: a link overrides whatever the recipient last picked. */
  it('prefers the environment the URL names over the stored one', () => {
    expect(make('?env=local', 'regression').service.getCurrent()).toBe('local');
  });

  /** The environment leads the path now (sqlx-e13.7); the old ?env= still works. */
  it('reads the environment from the front of the path, over storage', () => {
    expect(make('', 'regression', '/local/queries').service.getCurrent()).toBe('local');
  });

  it('prefers the path to a legacy ?env=', () => {
    expect(make('?env=other', 'regression', '/local/queries').service.getCurrent()).toBe('local');
  });

  it('names the default in the address once the backend has said what it is', () => {
    const {service, http} = make('', null);
    expect(service.urlName()).toBeNull();
    service.getEnvironments().subscribe();
    http.expectOne('/api/sql-explorer/environments').flush(list('regression', 'local'));
    expect(service.urlName()).toBe('regression');
  });

  it('falls back to the stored environment when the URL names none', () => {
    expect(make('', 'regression').service.getCurrent()).toBe('regression');
  });

  /** Null means "whatever the backend defaults to", not a name to invent. */
  it('selects nothing when neither the URL nor storage names one', () => {
    expect(make('', null).service.getCurrent()).toBeNull();
  });

  it('a link environment sticks for this browser', () => {
    make('?env=local', 'regression');
    expect(localStorage.getItem('sql-explorer.environment')).toBe('local');
  });

  /**
   * THE FAILURE THIS PREVENTS. The backend falls back to its default for an
   * unknown name, which is right for a stale header and wrong for a link: the
   * reader would be shown another corpus with nothing to say so.
   */
  it('reports an environment that does not exist, rather than substituting', () => {
    const {service, http} = make('?env=deleted-last-week', null);
    const seen: {value: string | null} = {value: null};
    service.unknown$.subscribe(name => seen.value = name);

    service.getEnvironments().subscribe();
    http.expectOne('/api/sql-explorer/environments').flush(list('regression', 'local'));

    expect(seen.value).toBe('deleted-last-week');
    expect(service.getCurrent()).toBe('deleted-last-week');
    http.verify();
  });

  it('reports nothing when the environment exists', () => {
    const {service, http} = make('?env=local', null);
    const seen: {value: string | null} = {value: 'set'};
    service.unknown$.subscribe(name => seen.value = name);

    service.getEnvironments().subscribe();
    http.expectOne('/api/sql-explorer/environments').flush(list('regression', 'local'));

    expect(seen.value).toBeNull();
    http.verify();
  });

  /** Nothing selected is not an unknown selection. */
  it('reports nothing when no environment is selected at all', () => {
    const {service, http} = make('', null);
    const seen: {value: string | null} = {value: 'set'};
    service.unknown$.subscribe(name => seen.value = name);

    service.getEnvironments().subscribe();
    http.expectOne('/api/sql-explorer/environments').flush(list('regression'));

    expect(seen.value).toBeNull();
    http.verify();
  });
});
