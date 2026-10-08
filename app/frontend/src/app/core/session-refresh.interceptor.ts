import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpContext, HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Router } from '@angular/router';
import { catchError, defer, finalize, firstValueFrom, from, map, Observable, of, shareReplay, switchMap, tap, throwError } from 'rxjs';

const INTERNAL = new HttpContextToken(() => false);
type Csrf = { token: string; headerName: string };

@Injectable({ providedIn: 'root' })
export class SessionRefresh {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private pending?: Observable<Csrf>;
  generation = 0;

  csrf() {
    return this.http.get<{ data: Csrf }>('/api/auth/csrf', {
      context: new HttpContext().set(INTERNAL, true),
    }).pipe(map(result => result.data));
  }

  refresh(generation: number): Observable<Csrf> {
    if (this.pending) return this.pending;
    if (generation !== this.generation) return this.csrf();
    const rotate = () => this.csrf().pipe(
      switchMap(csrf => this.http.post('/api/auth/refresh', {}, {
        headers: { [csrf.headerName]: csrf.token },
        context: new HttpContext().set(INTERNAL, true),
      })),
      switchMap(() => this.csrf()),
    );
    // Web Locks serialize cookie rotation across tabs. Recheck the shared session
    // after taking the lock because another tab may already have restored it.
    const recover = () => typeof navigator !== 'undefined' && navigator.locks
      ? from((async () => await navigator.locks.request('betterf-session-refresh', () => firstValueFrom(
          this.http.get('/api/auth/me', { context: new HttpContext().set(INTERNAL, true) }).pipe(
            map(() => true),
            catchError(error => error instanceof HttpErrorResponse && error.status === 401
              ? of(false) : throwError(() => error)),
            switchMap(active => active ? this.csrf() : rotate()),
          ),
        )))())
      : rotate();
    this.pending = defer(recover).pipe(
      tap(() => this.generation++),
      catchError(error => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          void this.router.navigateByUrl('/login');
        }
        return throwError(() => error);
      }),
      finalize(() => { this.pending = undefined; }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.pending;
  }

  beforeLogout(): Observable<unknown> {
    // Let an already-started refresh finish before revoking its replacement cookie.
    return this.pending?.pipe(catchError(() => of(null))) ?? of(null);
  }
}

export const sessionRefreshInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/') || request.context.get(INTERNAL)) return next(request);
  const sessions = inject(SessionRefresh);
  const generation = sessions.generation;
  const protectedRequest = request.url === '/api/auth/me' || /^\/api\/invitations(?:[/?]|$)/.test(request.url);
  const withCsrf = (csrf: Csrf) => request.clone({ setHeaders: { [csrf.headerName]: csrf.token } });
  const send = () => next(request).pipe(
    catchError(error => {
      // CSRF rejection precedes controller execution. Never retry generic 403s or network failures.
      if (error instanceof HttpErrorResponse && error.status === 403 && error.error?.error?.code === 'CSRF_INVALID') {
        return sessions.csrf().pipe(switchMap(csrf => next(withCsrf(csrf))));
      }
      return throwError(() => error);
    }),
    catchError(error => {
      if (protectedRequest && error instanceof HttpErrorResponse && error.status === 401
          && error.error?.error?.code === 'AUTHENTICATION_REQUIRED') {
        return sessions.refresh(generation).pipe(switchMap(csrf => next(withCsrf(csrf))));
      }
      return throwError(() => error);
    }),
  );
  return request.url === '/api/auth/logout'
    ? sessions.beforeLogout().pipe(switchMap(() => sessions.csrf()), switchMap(csrf => next(withCsrf(csrf))))
    : send();
};
