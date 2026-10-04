import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { IdentityStore } from './identity.store';
describe('Identity workflow', () => {
  let store: InstanceType<typeof IdentityStore>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [IdentityStore, provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(IdentityStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function csrf(token = 'masked-token') {
    http
      .expectOne('/api/auth/csrf')
      .flush({ data: { token, headerName: 'X-CSRF-TOKEN' }, error: null });
  }
  it('fetches CSRF for each mutation and preserves pending state across failed resend', () => {
    store.run({ kind: 'resend', email: 'ada@example.com' });
    expect(store.busy()).toBe(true);
    csrf();
    const first = http.expectOne('/api/registration/resend');
    expect(first.request.headers.get('X-CSRF-TOKEN')).toBe('masked-token');
    first.flush({
      data: { email: 'ada@example.com', resendAvailableAt: '2026-10-04T12:01:00Z' },
      error: null,
    });
    expect(store.pending()?.email).toBe('ada@example.com');
    expect(store.busy()).toBe(false);
    store.run({ kind: 'resend', email: 'ada@example.com' });
    csrf('new-token');
    const second = http.expectOne('/api/registration/resend');
    expect(second.request.headers.get('X-CSRF-TOKEN')).toBe('new-token');
    second.flush(
      { data: null, error: { code: 'RESEND_COOLDOWN', message: 'Wait 60 seconds.' } },
      { status: 429, statusText: 'Too many requests' },
    );
    expect(store.pending()?.resendAvailableAt).toBe('2026-10-04T12:01:00Z');
    expect(store.errorCode()).toBe('RESEND_COOLDOWN');
    expect(store.busy()).toBe(false);
  });
  it('can recover from failed verification without reporting success or retaining credentials', () => {
    store.run({ kind: 'verify', id: 'id', token: 'secret' });
    csrf();
    http
      .expectOne('/api/registration/verify')
      .flush(
        { data: null, error: { code: 'EXPIRED_VERIFICATION', message: 'Expired' } },
        { status: 400, statusText: 'Bad request' },
      );
    expect(store.verified()).toBe(false);
    expect(store.error()).toBe('Expired');
    store.run({ kind: 'verify', id: 'id', token: 'replacement' });
    csrf();
    http
      .expectOne('/api/registration/verify')
      .flush({
        data: { status: 'VERIFIED', account: { id: 'one', email: 'ada@example.com' } },
        error: null,
      });
    expect(store.verified()).toBe(true);
    expect(store.error()).toBe('');
  });
  it('does not submit concurrent actions and clears stale account when entering a page again', () => {
    store.run({ kind: 'login', email: 'ada@example.com', password: 'passphrase' });
    store.run({ kind: 'login', email: 'other@example.com', password: 'other' });
    csrf();
    http.expectOne('/api/auth/login').flush({
      data: { id: 'one', email: 'ada@example.com', organizationName: 'Acme' },
      error: null,
    });
    expect(store.account()?.id).toBe('one');
    store.clear();
    expect(store.account()).toBeNull();
  });
  it('clears the account when a later current-account check denies access', () => {
    store.run({ kind: 'current' });
    http.expectOne('/api/auth/me').flush({ data: { id: 'one' }, error: null });
    store.run({ kind: 'current' });
    http
      .expectOne('/api/auth/me')
      .flush(
        { data: null, error: { code: 'ACCESS_DENIED', message: 'Denied' } },
        { status: 403, statusText: 'Forbidden' },
      );
    expect(store.account()).toBeNull();
    expect(store.busy()).toBe(false);
  });
  it('refreshes queued delivery without blocking the form and ignores a response after leaving', () => {
    store.run({ kind: 'resend', email: 'ada@example.com' });
    csrf();
    http.expectOne('/api/registration/resend').flush({
      data: {
        email: 'ada@example.com',
        deliveryStatus: 'PENDING',
        resendAvailableAt: '2026-10-04T12:00:00Z',
      },
      error: null,
    });
    store.refreshDelivery('ada@example.com');
    expect(store.busy()).toBe(false);
    csrf();
    http.expectOne('/api/registration/status').flush({
      data: {
        email: 'ada@example.com',
        deliveryStatus: 'SMTP_ACCEPTED',
        resendAvailableAt: '2026-10-04T12:01:00Z',
      },
      error: null,
    });
    expect(store.pending()?.deliveryStatus).toBe('SMTP_ACCEPTED');
    store.refreshDelivery('ada@example.com');
    csrf();
    store.clear();
    http.expectOne('/api/registration/status').flush({
      data: {
        email: 'ada@example.com',
        deliveryStatus: 'FAILED',
        resendAvailableAt: '2026-10-04T12:00:00Z',
      },
      error: null,
    });
    expect(store.pending()).toBeNull();
  });
  it('does not treat an already-used link as an authenticated session', () => {
    store.run({ kind: 'verify', id: 'one', token: 'used-token' });
    csrf();
    http.expectOne('/api/registration/verify').flush({
      data: { status: 'ALREADY_VERIFIED', account: null },
      error: null,
    });
    expect(store.verified()).toBe(false);
    expect(store.alreadyVerified()).toBe(true);
    expect(store.account()).toBeNull();
  });
});
