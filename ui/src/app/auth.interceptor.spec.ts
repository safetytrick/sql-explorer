import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { authInterceptor } from './auth.interceptor';
import { BrowserNavigation, LOGIN_PATH } from './user.service';

/** Stands in for the browser: records where the page would have gone. */
class RecordingNavigation extends BrowserNavigation {
  assigned: string[] = [];
  where = '/queries?view=graph&tags=a,b';

  override here(): string {
    return this.where;
  }

  override assign(url: string): void {
    this.assigned.push(url);
  }
}

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let navigation: RecordingNavigation;

  beforeEach(() => {
    navigation = new RecordingNavigation();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: BrowserNavigation, useValue: navigation },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('sends the browser to sign in on a 401 from the API, coming back to this page', () => {
    let failed = false;
    http.get('/api/sql-explorer/environments').subscribe({ error: () => failed = true });
    backend.expectOne('/api/sql-explorer/environments').flush(
      { status: 401, login: '/api/sql-explorer/login' }, { status: 401, statusText: 'Unauthorized' });

    expect(navigation.assigned).toEqual([LOGIN_PATH + '?return=' + encodeURIComponent('/queries?view=graph&tags=a,b')]);
    // the caller still sees the error
    expect(failed).toBeTrue();
  });

  it('navigates once however many requests fail together', () => {
    http.get('/api/sql-explorer/environments').subscribe({ error: () => undefined });
    http.get('/api/sql-explorer/user').subscribe({ error: () => undefined });
    backend.expectOne('/api/sql-explorer/environments').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/sql-explorer/user').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(navigation.assigned.length).toBe(1);
  });

  it('leaves other errors to the caller', () => {
    http.get('/api/sql-explorer/tags').subscribe({ error: () => undefined });
    backend.expectOne('/api/sql-explorer/tags').flush(null, { status: 403, statusText: 'Forbidden' });
    http.get('/api/sql-explorer/tags').subscribe({ error: () => undefined });
    backend.expectOne('/api/sql-explorer/tags').flush(null, { status: 500, statusText: 'Server Error' });

    expect(navigation.assigned).toEqual([]);
  });

  it('ignores a 401 from anywhere but the API', () => {
    http.get('https://elsewhere.example.com/thing').subscribe({ error: () => undefined });
    backend.expectOne('https://elsewhere.example.com/thing').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(navigation.assigned).toEqual([]);
  });

  it('passes successful responses through untouched', () => {
    let body: unknown;
    http.get('/api/sql-explorer/user').subscribe(response => body = response);
    backend.expectOne('/api/sql-explorer/user').flush({ mode: 'none' });

    expect(body).toEqual({ mode: 'none' });
    expect(navigation.assigned).toEqual([]);
  });
});
