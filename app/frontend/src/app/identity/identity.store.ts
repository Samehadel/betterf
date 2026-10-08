import { inject } from '@angular/core';
import {
  patchState,
  signalStore,
  withComputed,
  withMethods,
  withState,
} from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, Observable, pipe, tap } from 'rxjs';
import { IdentityApi, Pending, Registration, Role } from './identity.api';
import { AuthStore } from '../core/auth.store';
type Action =
  | { kind: 'register'; body: Registration }
  | { kind: 'resend'; email: string }
  | { kind: 'verify'; id: string; token: string }
  | { kind: 'login'; email: string; password: string }
  | { kind: 'current' }
  | { kind: 'logout' };
export const IdentityStore = signalStore(
  withState({
    busy: false,
    error: '',
    errorCode: '',
    pending: null as Pending | null,
    verified: false,
    alreadyVerified: false,
    roles: [] as Role[],
    rolesError: false,
    signedOut: false,
  }),
  withComputed(() => ({ account: inject(AuthStore).account })),
  withMethods((store, api = inject(IdentityApi), auth = inject(AuthStore)) => ({
    clear() {
      patchState(store, {
        error: '',
        errorCode: '',
        pending: null,
        verified: false,
        alreadyVerified: false,
        signedOut: false,
      });
    },
    loadRoles: rxMethod<void>(
      pipe(
        exhaustMap(() =>
          api.roles().pipe(
            tap((roles) => patchState(store, { roles, rolesError: false })),
            catchError(() => {
              patchState(store, { rolesError: true });
              return EMPTY;
            }),
          ),
        ),
      ),
    ),
    refreshDelivery: rxMethod<string>(
      pipe(
        exhaustMap((email) =>
          api.deliveryStatus(email).pipe(
            tap((pending) => {
              if (store.pending()?.email === email)
                patchState(store, { pending });
            }),
            catchError(() => EMPTY),
          ),
        ),
      ),
    ),
    run: rxMethod<Action>(
      pipe(
        exhaustMap((action) => {
          patchState(store, {
            busy: true,
            error: '',
            errorCode: '',
            signedOut: false,
          });
          let operation: Observable<unknown>;
          switch (action.kind) {
            case 'register':
              operation = api
                .register(action.body)
                .pipe(tap((pending) => patchState(store, { pending })));
              break;
            case 'resend':
              operation = api
                .resend(action.email)
                .pipe(tap((pending) => patchState(store, { pending })));
              break;
            case 'verify':
              operation = api.verify(action.id, action.token).pipe(
                tap((result) => {
                  if (result.account) auth.setAccount(result.account);
                  patchState(store, {
                    verified: !!result.account,
                    alreadyVerified:
                      result.status === 'ALREADY_VERIFIED' && !result.account,
                  });
                }),
              );
              break;
            case 'login':
              operation = api
                .login(action.email, action.password)
                .pipe(tap((account) => auth.setAccount(account)));
              break;
            case 'current':
              operation = auth
                .load()
                .pipe(
                  tap(() =>
                    patchState(store, {
                      error: auth.error(),
                      errorCode: auth.errorCode(),
                    }),
                  ),
                );
              break;
            case 'logout':
              operation = api.logout().pipe(
                tap(() => {
                  auth.clear();
                  patchState(store, { signedOut: true });
                }),
              );
              break;
          }
          return operation.pipe(
            tap(() => patchState(store, { busy: false })),
            catchError((error) => {
              patchState(store, {
                busy: false,
                error: error?.error?.error?.message ?? '',
                errorCode: error?.error?.error?.code ?? 'UNAVAILABLE',
              });
              return EMPTY;
            }),
          );
        }),
      ),
    ),
  })),
);
