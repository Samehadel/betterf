import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, Observable, pipe, tap } from 'rxjs';
import { Account, IdentityApi, Pending, Registration, Role } from './identity.api';
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
    account: null as Account | null,
    roles: [] as Role[],
    rolesError: false,
    signedOut: false,
  }),
  withMethods((store, api = inject(IdentityApi)) => ({
    clear() {
      patchState(store, {
        error: '',
        errorCode: '',
        pending: null,
        verified: false,
        signedOut: false,
        account: null,
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
              if (store.pending()?.email === email) patchState(store, { pending });
            }),
            catchError(() => EMPTY),
          ),
        ),
      ),
    ),
    run: rxMethod<Action>(
      pipe(
        exhaustMap((action) => {
          patchState(store, { busy: true, error: '', errorCode: '', signedOut: false });
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
              operation = api
                .verify(action.id, action.token)
                .pipe(tap(() => patchState(store, { verified: true })));
              break;
            case 'login':
              operation = api
                .login(action.email, action.password)
                .pipe(tap((account) => patchState(store, { account })));
              break;
            case 'current':
              operation = api.current().pipe(tap((account) => patchState(store, { account })));
              break;
            case 'logout':
              operation = api
                .logout()
                .pipe(tap(() => patchState(store, { account: null, signedOut: true })));
              break;
          }
          return operation.pipe(
            tap(() => patchState(store, { busy: false })),
            catchError((error) => {
              patchState(store, {
                busy: false,
                ...(action.kind === 'current' ? { account: null } : {}),
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
