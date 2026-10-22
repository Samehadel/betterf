import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { StatusStore } from './status.store';
import { apiTimeoutInterceptor } from '../core/api-timeout.interceptor';

describe('Backend connection', () => {
  let store: InstanceType<typeof StatusStore>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [StatusStore, provideHttpClient(withInterceptors([apiTimeoutInterceptor])), provideHttpClientTesting()] });
    store = TestBed.inject(StatusStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); jest.useRealTimers(); });

  it('shows loading then connected for the documented response', () => {
    store.check();
    expect(store.connection()).toBe('loading');
    const request = http.expectOne('/api/status');
    expect(request.request.method).toBe('GET');
    request.flush({ data: { status: 'UP' }, error: null });
    expect(store.connection()).toBe('connected');
  });

  it('recovers from an unavailable backend on explicit retry', () => {
    store.check();
    http.expectOne('/api/status').flush({ data: null, error: { code: 'SERVICE_UNAVAILABLE', message: 'Unavailable' } }, { status: 503, statusText: 'Unavailable' });
    expect(store.connection()).toBe('unavailable');
    store.check();
    expect(store.connection()).toBe('loading');
    http.expectOne('/api/status').flush({ data: { status: 'UP' }, error: null });
    expect(store.connection()).toBe('connected');
  });

  it('rejects malformed success responses', () => {
    store.check();
    http.expectOne('/api/status').flush({ status: 'UP' });
    expect(store.connection()).toBe('unavailable');
  });

  it('bounds a hanging connection and allows another attempt', () => {
    jest.useFakeTimers();
    store.check();
    const request = http.expectOne('/api/status');
    jest.advanceTimersByTime(5001);
    expect(request.cancelled).toBe(true);
    expect(store.connection()).toBe('unavailable');
    store.check();
    http.expectOne('/api/status').flush({ data: { status: 'UP' }, error: null });
    expect(store.connection()).toBe('connected');
  });
});
