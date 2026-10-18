import { HttpRequest, HttpResponse } from '@angular/common/http';
import { Subject } from 'rxjs';
import { apiTimeoutInterceptor } from './api-timeout.interceptor';

describe('API timeout budgets', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('allows refinement to finish after the provider 45-second timeout budget', () => {
    const response = new Subject<HttpResponse<unknown>>();
    const next = jest.fn();
    const error = jest.fn();
    apiTimeoutInterceptor(
      new HttpRequest('POST', '/api/knowledge/tickets/refine', {}),
      () => response,
    ).subscribe({ next, error });
    jest.advanceTimersByTime(46000);
    expect(error).not.toHaveBeenCalled();
    response.next(
      new HttpResponse({
        body: { data: { field: 'title', text: 'Retry safely' } },
      }),
    );
    response.complete();
    expect(next).toHaveBeenCalledTimes(1);
    expect(error).not.toHaveBeenCalled();
  });

  it('still bounds a stalled refinement and keeps ordinary requests short', () => {
    const refinementError = jest.fn();
    const ordinaryError = jest.fn();
    apiTimeoutInterceptor(
      new HttpRequest('POST', '/api/knowledge/tickets/refine', {}),
      () => new Subject(),
    ).subscribe({ error: refinementError });
    apiTimeoutInterceptor(
      new HttpRequest('GET', '/api/status'),
      () => new Subject(),
    ).subscribe({ error: ordinaryError });
    jest.advanceTimersByTime(5000);
    expect(ordinaryError).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'TimeoutError' }),
    );
    expect(refinementError).not.toHaveBeenCalled();
    jest.advanceTimersByTime(45001);
    expect(refinementError).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'TimeoutError' }),
    );
  });
});
