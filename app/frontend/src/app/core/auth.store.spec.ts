import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AuthStore } from './auth.store';
import { Account } from '../identity/identity.api';

const account: Account = {
  id: 'one',
  fullName: 'Ada',
  email: 'ada@example.com',
  professionalRole: 'OTHER',
  organizationId: 'org',
  organizationName: 'Acme',
  accessRole: 'ADMIN',
};

describe('Shared authentication account', () => {
  let store: InstanceType<typeof AuthStore>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(AuthStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('shares startup requests and reuses the result across consumers', () => {
    const first = jest.fn();
    const second = jest.fn();
    store.load().subscribe(first);
    store.load().subscribe(second);
    expect(store.loading()).toBe(true);
    http.expectOne('/api/auth/me').flush({ data: account });
    store.load().subscribe();
    http.expectNone('/api/auth/me');
    expect(first).toHaveBeenCalledWith(account);
    expect(second).toHaveBeenCalledWith(account);
    expect(store.account()).toEqual(account);
    expect(store.loading()).toBe(false);
  });

  it.each([401, 403])(
    'remembers an anonymous or denied startup result (%s)',
    (status) => {
      store.load().subscribe();
      http
        .expectOne('/api/auth/me')
        .flush(
          { error: { code: 'ACCESS_DENIED' } },
          { status, statusText: 'Denied' },
        );
      store.load().subscribe();
      http.expectNone('/api/auth/me');
      expect(store.account()).toBeNull();
      expect(store.initialized()).toBe(true);
      expect(store.loading()).toBe(false);
    },
  );

  it('allows retry after a temporary failure without failing bootstrap', () => {
    const result = jest.fn();
    store.load().subscribe(result);
    http
      .expectOne('/api/auth/me')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    expect(result).toHaveBeenCalledWith(null);
    expect(store.initialized()).toBe(false);
    expect(store.loading()).toBe(false);
    expect(store.errorCode()).toBe('UNAVAILABLE');
    store.load().subscribe();
    http.expectOne('/api/auth/me').flush({ data: account });
    expect(store.errorCode()).toBe('');
    expect(store.account()).toEqual(account);
  });

  it.each(['login', 'logout'])(
    'ignores a startup response superseded by %s',
    (action) => {
      store.load().subscribe();
      if (action === 'login') store.setAccount({ ...account, id: 'new' });
      else store.clear();
      http.expectOne('/api/auth/me').flush({ data: account });
      expect(store.account()?.id ?? null).toBe(
        action === 'login' ? 'new' : null,
      );
      store.load().subscribe();
      http.expectNone('/api/auth/me');
    },
  );

  it('ignores an old failure after successful login', () => {
    store.load().subscribe();
    store.setAccount(account);
    http
      .expectOne('/api/auth/me')
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(store.account()).toEqual(account);
    expect(store.errorCode()).toBe('');
  });
});
