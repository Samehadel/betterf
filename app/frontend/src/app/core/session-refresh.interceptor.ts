import { inject, Injectable } from '@angular/core';
import {
  HttpClient,
  HttpContext,
  HttpContextToken,
  HttpErrorResponse,
  HttpInterceptorFn,
} from '@angular/common/http';
import { Router } from '@angular/router';
import {
  catchError,
  defer,
  finalize,
  firstValueFrom,
  from,
  map,
  Observable,
  of,
  shareReplay,
  switchMap,
  tap,
  throwError,
} from 'rxjs';

import { AuthStore } from './auth.store';
import { Account } from '../identity/identity.api';

const INTERNAL = new HttpContextToken(() => false);
type Csrf = { token: string; headerName: string };

@Injectable({ providedIn: 'root' })
export class SessionRefresh {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  private pending?: Observable<Csrf>;
  generation = 0;

  csrf() {
    return this.http
      .get<{ data: Csrf }>('/api/auth/csrf', {
        context: new HttpContext().set(INTERNAL, true),
      })
      .pipe(map((result) => result.data));
  }

  refresh(generation: number): Observable<Csrf> {
    if (this.pending) return this.pending;
    if (generation !== this.generation) return this.csrf();
    this.pending = defer(() => this.recover()).pipe(
      tap(() => this.generation++),
      catchError((error) => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          if (this.auth.account()) this.auth.clear();
        }
        return throwError(() => error);
      }),
      finalize(() => {
        this.pending = undefined;
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.pending;
  }

  private rotate(): Observable<Csrf> {
    return this.csrf().pipe(
      switchMap((csrf) =>
        this.http.post<{ data: Account }>(
          '/api/auth/refresh',
          {},
          {
            headers: { [csrf.headerName]: csrf.token },
            context: new HttpContext().set(INTERNAL, true),
          },
        ),
      ),
      tap((result) => this.auth.setAccount(result.data)),
      switchMap(() => this.csrf()),
    );
  }

  private recover(): Observable<Csrf> {
    if (typeof navigator === 'undefined' || !navigator.locks)
      return this.rotate();
    // Serialize cookie rotation across tabs and reuse an already-restored session.
    return from(
      (async () =>
        await navigator.locks.request('betterf-session-refresh', () =>
          firstValueFrom(this.sharedSession()),
        ))(),
    );
  }

  private sharedSession(): Observable<Csrf> {
    return this.http
      .get<{ data: Account }>('/api/auth/me', {
        context: new HttpContext().set(INTERNAL, true),
      })
      .pipe(
        tap((result) => this.auth.setAccount(result.data)),
        map(() => true),
        catchError((error) =>
          error instanceof HttpErrorResponse && error.status === 401
            ? of(false)
            : throwError(() => error),
        ),
        switchMap((active) => (active ? this.csrf() : this.rotate())),
      );
  }

  beforeLogout(): Observable<unknown> {
    // Let an already-started refresh finish before revoking its replacement cookie.
    return this.pending?.pipe(catchError(() => of(null))) ?? of(null);
  }
}

export const sessionRefreshInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/') || request.context.get(INTERNAL))
    return next(request);
  const sessions = inject(SessionRefresh);
  const auth = inject(AuthStore);
  const router = inject(Router);
  const generation = sessions.generation;
  const protectedRequest =
    request.url === '/api/auth/me' ||
    /^\/api\/invitations(?:[/?]|$)/.test(request.url);
  const withCsrf = (csrf: Csrf) =>
    request.clone({ setHeaders: { [csrf.headerName]: csrf.token } });
  const send = () =>
    next(request).pipe(
      catchError((error) => {
        // CSRF rejection precedes controller execution. Never retry generic 403s or network failures.
        if (
          error instanceof HttpErrorResponse &&
          error.status === 403 &&
          error.error?.error?.code === 'CSRF_INVALID'
        ) {
          return sessions
            .csrf()
            .pipe(switchMap((csrf) => next(withCsrf(csrf))));
        }
        return throwError(() => error);
      }),
      catchError((error) => {
        if (
          protectedRequest &&
          error instanceof HttpErrorResponse &&
          error.status === 401 &&
          error.error?.error?.code === 'AUTHENTICATION_REQUIRED'
        ) {
          return sessions
            .refresh(generation)
            .pipe(switchMap((csrf) => next(withCsrf(csrf))));
        }
        return throwError(() => error);
      }),
      catchError((error) => {
        if (
          protectedRequest &&
          error instanceof HttpErrorResponse &&
          error.status === 401
        ) {
          if (auth.account()) auth.clear();
          // Startup probes can be anonymous on public pages. Protected operations
          // still take the user to login when their session cannot be restored.
          if (request.url !== '/api/auth/me')
            void router.navigateByUrl('/login');
        }
        return throwError(() => error);
      }),
    );
  return request.url === '/api/auth/logout'
    ? sessions.beforeLogout().pipe(
        switchMap(() => sessions.csrf()),
        switchMap((csrf) => next(withCsrf(csrf))),
      )
    : send();
};
