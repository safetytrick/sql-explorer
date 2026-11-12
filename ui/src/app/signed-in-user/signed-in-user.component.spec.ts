import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { SignedInUserComponent } from './signed-in-user.component';
import { BrowserNavigation, CurrentUser } from '../user.service';

class RecordingNavigation extends BrowserNavigation {
  assigned: string[] = [];

  override assign(url: string): void {
    this.assigned.push(url);
  }
}

const SIGNED_IN: CurrentUser = {
  mode: 'oidc',
  user: { id: 7, email: 'reader@example.com', name: 'Reader One', createdOn: new Date(0) },
  issuer: 'http://localhost:18190',
  subject: 'reader',
  groups: ['sqlx:viewer:local', 'sqlx:runner:local/app'],
  grants: ['viewer:local', 'runner:local/app'],
  admin: false,
  environments: {local: ['explorer:read', 'commentary:write']},
};

const SIGN_IN_OFF: CurrentUser = {
  mode: 'none',
  user: { id: 1, email: 'admin', createdOn: new Date(0) },
  issuer: null,
  subject: null,
  groups: [],
  grants: ['admin:*'],
  admin: true,
  environments: {},
};

describe('SignedInUserComponent', () => {
  let fixture: ComponentFixture<SignedInUserComponent>;
  let backend: HttpTestingController;
  let navigation: RecordingNavigation;

  beforeEach(() => {
    navigation = new RecordingNavigation();
    TestBed.configureTestingModule({
      imports: [SignedInUserComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BrowserNavigation, useValue: navigation },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SignedInUserComponent);
    fixture.detectChanges();
  });

  afterEach(() => backend.verify());

  function answer(user: CurrentUser) {
    backend.expectOne('/api/sql-explorer/user').flush(user);
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;

  it('shows who is signed in, with their groups on hover, and Sign out', () => {
    answer(SIGNED_IN);

    const who = el().querySelector('.who')!;
    expect(who.textContent!.trim()).toBe('Reader One');
    expect(who.getAttribute('title')).toContain('reader@example.com');
    expect(who.getAttribute('title')).toContain('sqlx:viewer:local, sqlx:runner:local/app');
    expect(el().querySelector('.sign-out')!.textContent!.trim()).toBe('Sign out');
    expect(el().querySelector('.auth-off')).toBeNull();
  });

  it('links to the access tokens page when signed in (sqlx-e13.5)', () => {
    answer(SIGNED_IN);
    expect(el().querySelector('a.tokens')!.getAttribute('href')).toBe('/tokens');
  });

  it('offers no tokens link with sign-in off', () => {
    answer(SIGN_IN_OFF);
    expect(el().querySelector('a.tokens')).toBeNull();
  });

  it('falls back to the email when the provider sends no name', () => {
    answer({ ...SIGNED_IN, user: { ...SIGNED_IN.user, name: null } });

    expect(el().querySelector('.who')!.textContent!.trim()).toBe('reader@example.com');
  });

  it('says sign-in is not configured, and offers no sign-out, when it is off', () => {
    answer(SIGN_IN_OFF);

    expect(el().querySelector('.auth-off')!.textContent!.trim()).toBe('sign-in not configured');
    expect(el().querySelector('.who')).toBeNull();
    expect(el().querySelector('.sign-out')).toBeNull();
  });

  it('signs out, then follows the server to the provider', () => {
    answer(SIGNED_IN);

    (el().querySelector('.sign-out') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el().querySelector('.sign-out')!.textContent!.trim()).toBe('signing out…');

    const logout = backend.expectOne('/api/sql-explorer/logout');
    expect(logout.request.method).toBe('POST');
    logout.flush({ location: 'http://localhost:18190/connect/logout?id_token_hint=x' });

    expect(navigation.assigned).toEqual(['http://localhost:18190/connect/logout?id_token_hint=x']);
  });

  it('shows nothing when /user cannot be read', () => {
    backend.expectOne('/api/sql-explorer/user').flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(el().textContent!.trim()).toBe('');
  });

  it('says so when signed in with no environment granted (sqlx-e13.4)', () => {
    answer(SIGNED_IN);
    const component = fixture.componentInstance;
    expect(component.noAccess({...SIGNED_IN, environments: {}})).toBeTrue();
    expect(component.noAccess(SIGNED_IN)).toBeFalse();
    expect(component.noAccess({...SIGNED_IN, environments: {}, admin: true})).toBeFalse();
    expect(component.noAccess(SIGN_IN_OFF)).toBeFalse();
  });
});
