import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { Account } from '../identity/identity.api';
import { AuthStore } from './auth.store';
import { sessionRefreshInterceptor } from './session-refresh.interceptor';

describe('Session recovery', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  const router = { navigateByUrl: jest.fn() };
  const rejected = (code = 'AUTHENTICATION_REQUIRED') => ({ error: { code } });
  beforeEach(() => {
    router.navigateByUrl.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([sessionRefreshInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function csrf(token = 'fresh') {
    http
      .expectOne('/api/auth/csrf')
      .flush({ data: { token, headerName: 'X-CSRF-TOKEN' } });
  }
  function refresh() {
    csrf();
    const request = http.expectOne('/api/auth/refresh');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('fresh');
    request.flush({ data: { id: 'refreshed', accessRole: 'ADMIN' } });
    expect(TestBed.inject(AuthStore).account()?.id).toBe('refreshed');
    csrf('rotated');
  }
  it('shares refresh for simultaneous 401s and preserves the original mutation', () => {
    const result = jest.fn();
    client.get('/api/auth/me').subscribe();
    client
      .post('/api/invitations', { email: 'a@example.com' })
      .subscribe(result);
    http
      .expectOne('/api/auth/me')
      .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
    http
      .expectOne('/api/invitations')
      .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
    refresh();
    http.expectOne('/api/auth/me').flush({ data: {} });
    const retry = http.expectOne('/api/invitations');
    expect(retry.request.body).toEqual({ email: 'a@example.com' });
    expect(retry.request.headers.get('X-CSRF-TOKEN')).toBe('rotated');
    retry.flush({ data: 'sent' });
    expect(result).toHaveBeenCalledWith({ data: 'sent' });
  });
  it('renews stale CSRF before recovering an expired session', () => {
    client.post('/api/invitations', {}).subscribe();
    http.expectOne('/api/invitations').flush(rejected('CSRF_INVALID'), {
      status: 403,
      statusText: 'Forbidden',
    });
    csrf();
    http
      .expectOne('/api/invitations')
      .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
    refresh();
    http.expectOne('/api/invitations').flush({});
  });
  it('does not rotate twice for a delayed response from the old session', () => {
    client.get('/api/auth/me').subscribe();
    client.get('/api/invitations').subscribe();
    const late = http.expectOne('/api/invitations');
    http
      .expectOne('/api/auth/me')
      .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
    refresh();
    http.expectOne('/api/auth/me').flush({});
    late.flush(rejected(), { status: 401, statusText: 'Unauthorized' });
    csrf();
    http.expectOne('/api/invitations').flush({});
  });
  it('ends recovery after one retry', () => {
    const error = jest.fn();
    client.get('/api/auth/me').subscribe({ error });
    http
      .expectOne('/api/auth/me')
      .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
    refresh();
    http
      .expectOne('/api/auth/me')
      .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
    expect(error).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(AuthStore).account()).toBeNull();
  });
  it.each([403, 500, 0])(
    'does not replay permission, server, or network failures (%s)',
    (status) => {
      const error = jest.fn();
      client.post('/api/invitations', {}).subscribe({ error });
      http
        .expectOne('/api/invitations')
        .flush(rejected('ACCESS_DENIED'), { status, statusText: 'Failure' });
      expect(error).toHaveBeenCalledTimes(1);
    },
  );
  it('redirects to login when the refresh credential has expired', () => {
    const error = jest.fn();
    TestBed.inject(AuthStore).setAccount({ id: 'stale' } as Account);
    client.get('/api/invitations').subscribe({ error });
    http
      .expectOne('/api/invitations')
      .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
    csrf();
    http.expectOne('/api/auth/refresh').flush(rejected('SESSION_EXPIRED'), {
      status: 401,
      statusText: 'Unauthorized',
    });
    expect(error).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
    expect(TestBed.inject(AuthStore).account()).toBeNull();
  });
  it('does not refresh login failures or send credentials to another origin', () => {
    client.post('/api/auth/login', {}).subscribe({ error: () => {} });
    http
      .expectOne('/api/auth/login')
      .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
    client
      .get('https://other.example/api/auth/me')
      .subscribe({ error: () => {} });
    http
      .expectOne('https://other.example/api/auth/me')
      .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
  });
  it.each(['/api/auth/logout', '/api/auth/invitation/preview'])(
    'waits for in-flight refresh before %s revokes its replacement cookie',
    (url) => {
      client.get('/api/auth/me').subscribe();
      http
        .expectOne('/api/auth/me')
        .flush(rejected(), { status: 401, statusText: 'Unauthorized' });
      client.post(url, {}).subscribe();
      http.expectNone(url);
      refresh();
      http.expectOne('/api/auth/me').flush({});
      csrf('logout');
      const logout = http.expectOne(url);
      expect(logout.request.headers.get('X-CSRF-TOKEN')).toBe('logout');
      logout.flush(null);
    },
  );
});

describe('Cross-tab session recovery', () => {
  it('rechecks the shared session under a browser lock before rotating', async () => {
    const locks = {
      request: jest.fn((_name: string, action: () => Promise<unknown>) =>
        action(),
      ),
    };
    Object.defineProperty(navigator, 'locks', {
      value: locks,
      configurable: true,
    });
    try {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([sessionRefreshInterceptor])),
          provideHttpClientTesting(),
          { provide: Router, useValue: { navigateByUrl: jest.fn() } },
        ],
      });
      const http = TestBed.inject(HttpTestingController);
      const result = jest.fn();
      TestBed.inject(HttpClient).get('/api/auth/me').subscribe(result);
      http
        .expectOne('/api/auth/me')
        .flush(
          { error: { code: 'AUTHENTICATION_REQUIRED' } },
          { status: 401, statusText: 'Unauthorized' },
        );
      expect(locks.request).toHaveBeenCalledTimes(1);
      http.expectOne('/api/auth/me').flush({ data: { id: 'shared' } });
      expect(TestBed.inject(AuthStore).account()?.id).toBe('shared');
      http
        .expectOne('/api/auth/csrf')
        .flush({ data: { token: 'shared', headerName: 'X-CSRF-TOKEN' } });
      await locks.request.mock.results[0].value;
      await Promise.resolve();
      http.expectOne('/api/auth/me').flush({ data: 'restored' });
      expect(result).toHaveBeenCalledWith({ data: 'restored' });
      http.verify();
    } finally {
      Reflect.deleteProperty(navigator, 'locks');
    }
  });
});
