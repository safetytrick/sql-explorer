import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environmentInterceptor, withEnvironment } from './environment.interceptor';
import { EnvironmentService } from './environment.service';
import { INITIAL_SEARCH } from './environment-url';

describe('environmentInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let environments: EnvironmentService;

  const LIST = {defaultEnvironment: 'alpha', environments: [{name: 'alpha'}, {name: 'beta'}]};

  beforeEach(() => {
    localStorage.removeItem('sql-explorer.environment');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([environmentInterceptor])),
        provideHttpClientTesting(),
        {provide: INITIAL_SEARCH, useValue: ''},
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    environments = TestBed.inject(EnvironmentService);
  });

  afterEach(() => {
    backend.verify();
    localStorage.removeItem('sql-explorer.environment');
  });

  it('waits for the environment list, then sends the default environment in the path', () => {
    http.get('/api/sql-explorer/schemas').subscribe();
    backend.expectOne('/api/sql-explorer/environments').flush(LIST);
    backend.expectOne('/api/sql-explorer/env/alpha/schemas').flush([]);
  });

  it('sends the selected environment once the list is known', () => {
    environments.getEnvironments().subscribe();
    backend.expectOne('/api/sql-explorer/environments').flush(LIST);
    environments.setCurrent('beta');
    http.post('/api/sql-explorer/queries/search', {}).subscribe();
    backend.expectOne('/api/sql-explorer/env/beta/queries/search').flush({});
  });

  it('falls back to the default, in the open, for an environment the backend does not have', () => {
    environments.setCurrent('gone');
    environments.getEnvironments().subscribe();
    backend.expectOne('/api/sql-explorer/environments').flush(LIST);
    http.get('/api/sql-explorer/tables').subscribe();
    backend.expectOne('/api/sql-explorer/env/alpha/tables').flush([]);
    expect(environments.unknown$.value).toBe('gone');
  });

  it('leaves global endpoints and other hosts alone', () => {
    http.get('/api/sql-explorer/environments').subscribe();
    http.get('/api/sql-explorer/user').subscribe();
    http.post('/api/sql-explorer/util/clean', 'x').subscribe();
    http.get('/api/sql-explorer/graph/layout').subscribe();
    http.get('/assets/config.json').subscribe();
    backend.expectOne('/api/sql-explorer/environments').flush(LIST);
    backend.expectOne('/api/sql-explorer/user').flush({});
    backend.expectOne('/api/sql-explorer/util/clean').flush({});
    backend.expectOne('/api/sql-explorer/graph/layout').flush({});
    backend.expectOne('/assets/config.json').flush({});
  });

  it('encodes the environment as one path segment', () => {
    expect(withEnvironment('/api/sql-explorer/tables/app', 'a b/c')).toBe('/api/sql-explorer/env/a%20b%2Fc/tables/app');
  });
});
