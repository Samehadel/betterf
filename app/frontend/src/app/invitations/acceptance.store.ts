import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, pipe, switchMap, tap } from 'rxjs';
import { Account } from '../identity/identity.api';
import {
  AcceptanceApi,
  AcceptancePreview,
  InvitationLink,
  MemberRegistration,
} from './acceptance.api';

export const AcceptanceStore = signalStore(
  withState({
    preview: null as AcceptancePreview | null,
    account: null as Account | null,
    loading: false,
    submitting: false,
    error: '',
    errorCode: '',
  }),
  withMethods((store, api = inject(AcceptanceApi)) => ({
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
          return api.preview(link).pipe(
            tap((preview) => patchState(store, { preview, loading: false })),
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
                  known &&
                  ![
                    'INVALID_REQUEST',
                    'SIGN_IN_REQUIRED',
                    'ACCOUNT_MISMATCH',
                  ].includes(code)
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
