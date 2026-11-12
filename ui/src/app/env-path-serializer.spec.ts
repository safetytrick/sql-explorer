import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {EnvPathUrlSerializer} from './env-path-serializer';
import {EnvironmentService} from './environment.service';
import {INITIAL_PATH, INITIAL_SEARCH} from './environment-url';

describe('EnvPathUrlSerializer', () => {
  let serializer: EnvPathUrlSerializer;
  let environments: EnvironmentService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        EnvPathUrlSerializer, provideHttpClient(), provideHttpClientTesting(),
        {provide: INITIAL_SEARCH, useValue: ''}, {provide: INITIAL_PATH, useValue: '/'},
      ],
    });
    serializer = TestBed.inject(EnvPathUrlSerializer);
    environments = TestBed.inject(EnvironmentService);
  });

  afterEach(() => localStorage.clear());

  it('gives the router the app path, without the environment', () => {
    const tree = serializer.parse('/prod/table/app/orders?columns=app.orders.id#x');
    expect(tree.root.children['primary'].segments.map(s => s.path)).toEqual(['table', 'app', 'orders']);
    expect(tree.queryParams).toEqual({columns: 'app.orders.id'});
    expect(tree.fragment).toBe('x');
  });

  it('writes the selected environment in front of every URL', () => {
    environments.setCurrent('prod');
    expect(serializer.serialize(serializer.parse('/queries?tags=a'))).toBe('/prod/queries?tags=a');
    expect(serializer.serialize(serializer.parse('/staging/queries'))).toBe('/prod/queries');
  });

  it('rewrites a legacy ?env= link to the path form', () => {
    environments.setCurrent('prod');
    expect(serializer.serialize(serializer.parse('/tables?env=prod&tags=a'))).toBe('/prod/tables?tags=a');
  });

  it('names the backend default once it is known, and nothing before', () => {
    expect(serializer.serialize(serializer.parse('/tables'))).toBe('/tables');
    environments.getEnvironments().subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/sql-explorer/environments')
      .flush({defaultEnvironment: 'regression', environments: []});
    expect(serializer.serialize(serializer.parse('/tables'))).toBe('/regression/tables');
  });
});
