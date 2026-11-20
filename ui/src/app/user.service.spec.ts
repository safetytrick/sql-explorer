import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { CurrentUser, UserService } from './user.service';
import { EnvironmentService } from './environment.service';

const VISITOR: CurrentUser = {
  mode: 'oidc', user: null, issuer: null, subject: null, groups: [],
  grants: ['reader:local'], admin: false, environments: {local: ['explorer:read']}, anonymous: true,
};

const VIEWER: CurrentUser = {
  ...VISITOR, anonymous: false, grants: ['viewer:local'],
  environments: {local: ['explorer:read', 'commentary:write']},
};

describe('UserService', () => {
  let service: UserService;
  let backend: HttpTestingController;
  let env: string | null;

  beforeEach(() => {
    env = 'local';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EnvironmentService, useValue: { urlName: () => env } },
      ],
    });
    service = TestBed.inject(UserService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  function answer(user: CurrentUser) {
    service.getCurrentUser().subscribe();
    backend.expectOne('/api/sql-explorer/user').flush(user);
  }

  it('denies nothing, and asks nothing, until someone else has asked /user (sqlx-387)', () => {
    expect(service.denied('commentary:write')).toBeFalse();
    backend.expectNone('/api/sql-explorer/user');
  });

  it('denies a visitor commentary but not reading', () => {
    answer(VISITOR);
    expect(service.denied('commentary:write')).toBeTrue();
    expect(service.denied('explorer:read')).toBeFalse();
  });

  it('denies a viewer nothing they hold', () => {
    answer(VIEWER);
    expect(service.denied('commentary:write')).toBeFalse();
  });

  it('denies nothing while the environment is not yet known', () => {
    env = null;
    answer(VISITOR);
    expect(service.denied('commentary:write')).toBeFalse();
  });
});
