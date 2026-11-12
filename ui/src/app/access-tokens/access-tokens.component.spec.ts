import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {provideRouter, Router} from '@angular/router';
import {AccessTokensComponent} from './access-tokens.component';
import {
  AccessTokenList, AccessTokenView, lifetimeChoices, optionsByScope, readTokensView, refusal, tokensViewParams
} from './access-tokens';
import {convertToParamMap} from '@angular/router';

const ACTIVE: AccessTokenView = {
  id: 4, name: 'laptop MCP', prefix: 'sqlx_AbCd1234', ceiling: ['viewer:regression'],
  createdOn: '2026-11-01T10:00:00Z', expiresOn: '2027-01-30T10:00:00Z', lastUsedOn: null, revokedOn: null,
  status: 'active', owner: null
};

const REVOKED: AccessTokenView = {
  ...ACTIVE, id: 3, name: 'old', prefix: 'sqlx_ZzZz9999', status: 'revoked', revokedOn: '2026-11-02T10:00:00Z'
};

const LIST: AccessTokenList = {
  enabled: true, tokens: [ACTIVE, REVOKED], grants: ['admin:*'], maxLifetimeDays: 90, admin: true,
  ceilingOptions: ['admin:*', 'author:*', 'runner:*', 'viewer:*', 'viewer:regression', 'viewer:staging']
};

describe('access tokens: the pure half', () => {
  it('keeps the view in the URL, defaults left out', () => {
    expect(tokensViewParams({all: false, creating: false})).toEqual({'var-all': null, 'var-new': null});
    expect(tokensViewParams({all: true, creating: true})).toEqual({'var-all': 'true', 'var-new': 'true'});
    expect(readTokensView(convertToParamMap({'var-all': 'true'}))).toEqual({all: true, creating: false});
    expect(readTokensView(convertToParamMap({'var-new': 'yes please'}))).toEqual({all: false, creating: false});
  });

  it('offers round lifetimes under the maximum, then the maximum', () => {
    expect(lifetimeChoices(90)).toEqual([1, 7, 30, 90]);
    expect(lifetimeChoices(14)).toEqual([1, 7, 14]);
    expect(lifetimeChoices(1)).toEqual([1]);
  });

  it('groups ceiling options by where they apply', () => {
    expect(optionsByScope(['admin:*', 'viewer:*', 'runner:prod/app', 'viewer:prod/app', 'viewer:prod'])).toEqual([
      {scope: 'everywhere', grants: ['admin:*', 'viewer:*']},
      {scope: 'prod/app', grants: ['runner:prod/app', 'viewer:prod/app']},
      {scope: 'prod', grants: ['viewer:prod']}
    ]);
  });

  it('shows the server\'s reason for a refusal as written', () => {
    expect(refusal({status: 400, error: {message: 'A token cannot have more access than you'}}))
      .toBe('A token cannot have more access than you');
    expect(refusal({status: 500, error: 'boom'})).toBe('HTTP 500');
  });
});

describe('AccessTokensComponent', () => {
  let fixture: ComponentFixture<AccessTokensComponent>;
  let backend: HttpTestingController;
  let router: Router;

  async function render(url = '/tokens') {
    TestBed.configureTestingModule({
      imports: [AccessTokensComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{path: 'tokens', component: AccessTokensComponent}, {path: 'admin/mcp', children: []}])]
    });
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    await router.navigateByUrl(url);
    fixture = TestBed.createComponent(AccessTokensComponent);
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;

  function answer(list: AccessTokenList, all = false) {
    const request = backend.expectOne(r => r.url === '/api/sql-explorer/tokens' && r.method === 'GET');
    expect(request.request.params.get('all')).toBe(all ? 'true' : null);
    request.flush(list);
    fixture.detectChanges();
  }

  afterEach(() => backend.verify());

  it('lists the tokens, without their secrets, and offers Revoke only on active ones', async () => {
    await render();
    answer(LIST);
    const rows = el().querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('laptop MCP');
    expect(rows[0].textContent).toContain('sqlx_AbCd1234…');
    expect(rows[0].textContent).toContain('viewer:regression');
    expect(rows[0].textContent).toContain('never');
    expect(rows[0].querySelector('button.revoke')).not.toBeNull();
    expect(rows[1].querySelector('button.revoke')).toBeNull();
    expect(rows[1].querySelector('.status')!.getAttribute('data-status')).toBe('revoked');
  });

  it('revokes on the second click, then lists again', async () => {
    await render();
    answer(LIST);
    const button = () => el().querySelector('tr[data-token="4"] button.revoke') as HTMLButtonElement;
    button().click();
    fixture.detectChanges();
    expect(button().textContent).toContain('Really revoke?');
    backend.expectNone('/api/sql-explorer/tokens/4');
    button().click();
    const revoke = backend.expectOne('/api/sql-explorer/tokens/4');
    expect(revoke.request.method).toBe('DELETE');
    revoke.flush({...ACTIVE, status: 'revoked'});
    answer({...LIST, tokens: [{...ACTIVE, status: 'revoked'}, REVOKED]});
    expect(el().querySelectorAll('button.revoke').length).toBe(0);
  });

  it('opens the form from the URL, makes a token from the chosen ceiling, and shows the secret once', async () => {
    await render('/tokens?var-new=true');
    answer(LIST);
    const form = el().querySelector('.create-card')!;
    expect(form).not.toBeNull();
    expect(form.querySelectorAll('.scope').length).toBe(3);

    const create = form.querySelector('button.create') as HTMLButtonElement;
    expect(create.disabled).withContext('needs a name and a ceiling').toBeTrue();

    // ngModel writes its first value asynchronously; type after it has
    await fixture.whenStable();
    const name = form.querySelector('input.token-name') as HTMLInputElement;
    name.value = 'laptop MCP';
    name.dispatchEvent(new Event('input'));
    (form.querySelector('input[data-grant="viewer:regression"]') as HTMLInputElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.name).toBe('laptop MCP');
    expect(create.disabled).toBeFalse();

    create.click();
    const request = backend.expectOne(r => r.url === '/api/sql-explorer/tokens' && r.method === 'POST');
    expect(request.request.body).toEqual({name: 'laptop MCP', ceiling: ['viewer:regression'], expiresInDays: 90});
    request.flush({token: ACTIVE, secret: 'sqlx_secret-shown-once'});
    await fixture.whenStable();
    answer(LIST);

    expect(el().querySelector('.secret')!.textContent).toBe('sqlx_secret-shown-once');
    // the form closed, and the secret is nowhere in the address
    expect(router.url).toBe('/tokens');
    expect(el().querySelector('.create-card')).toBeNull();

    (el().querySelector('.secret-card .btn:not(.copy-secret)') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el().querySelector('.secret')).toBeNull();
  });

  it('shows why a token was refused, as the server says it', async () => {
    await render('/tokens?var-new=true');
    answer(LIST);
    fixture.componentInstance.name = 'too much';
    fixture.componentInstance.toggle('admin:*', true);
    fixture.componentInstance.create();
    backend.expectOne(r => r.method === 'POST').flush(
      {message: 'A token cannot have more access than you: admin:* is beyond your own grants (viewer:regression).'},
      {status: 400, statusText: 'Bad Request'});
    fixture.detectChanges();
    expect(el().querySelector('.create-error')!.textContent).toContain('beyond your own grants');
  });

  it('lets an administrator list everyone\'s, in the URL', async () => {
    await render('/tokens?var-all=true');
    answer({...LIST, tokens: [{...ACTIVE, owner: {id: 9, email: 'viewer@example.com', name: null}}]}, true);
    expect(el().querySelector('td.owner')!.textContent).toContain('viewer@example.com');
    expect((el().querySelector('.all-toggle input') as HTMLInputElement).checked).toBeTrue();
  });

  it('says tokens are not needed with sign-in off', async () => {
    await render();
    answer({...LIST, enabled: false, tokens: [], admin: false});
    expect(el().querySelector('.disabled-note')!.textContent).toContain('Sign-in is not configured');
    expect(el().querySelector('.new-token')).toBeNull();
  });
});
