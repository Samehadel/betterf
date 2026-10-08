import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, forkJoin, pipe, switchMap, tap } from 'rxjs';
import { IdentityApi, Account, Role } from '../identity/identity.api';
import {
  AcceptanceApi,
  AcceptancePreview,
  InvitationLink,
  MemberRegistration,
} from './acceptance.api';

export const AcceptanceStore = signalStore(
  withState({
    preview: null as AcceptancePreview | null,
    roles: [] as Role[],
    account: null as Account | null,
    loading: false,
    submitting: false,
    error: '',
    errorCode: '',
  }),
  withMethods((store, api = inject(AcceptanceApi), identity = inject(IdentityApi)) => ({
    load: rxMethod<InvitationLink>(
      pipe(
        switchMap((link) => {
          patchState(store, {
            loading: true,
            preview: null,
            error: '',
            errorCode: '',
            account: null,
          });
          return forkJoin({
            preview: api.preview(link),
            roles: identity.roles(),
          }).pipe(
            tap((result) => patchState(store, { ...result, loading: false })),
            catchError(() => {
              patchState(store, {
                loading: false,
                error: 'acceptance.loadFailed',
                errorCode: 'UNAVAILABLE',
              });
              return EMPTY;
            }),
          );
        }),
      ),
    ),
    accept: rxMethod<MemberRegistration>(
      pipe(
        exhaustMap((body) => {
          patchState(store, { submitting: true, error: '', errorCode: '' });
          return api.accept(body).pipe(
            tap((account) => patchState(store, { account, submitting: false })),
            catchError((error) => {
              const code = error?.error?.error?.code;
              const known = error?.status >= 400 && error?.status < 500;
              patchState(store, {
                submitting: false,
                error: known
                  ? (error?.error?.error?.message ?? 'acceptance.uncertain')
                  : 'acceptance.uncertain',
                errorCode: code ?? 'UNAVAILABLE',
                preview:
                  known && !['INVALID_REQUEST', 'INVALID_ROLE'].includes(code)
                    ? null
                    : store.preview(),
              });
              return EMPTY;
            }),
          );
        }),
      ),
    ),
  })),
);
