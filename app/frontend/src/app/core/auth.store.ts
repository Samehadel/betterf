import { WorkspaceContext } from './workspace-context';
import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { catchError, finalize, Observable, of, shareReplay, tap } from 'rxjs';
import { Account, IdentityApi } from '../identity/identity.api';

/** Owns the in-memory account for the lifetime of the application. */
export const AuthStore = signalStore(
  { providedIn: 'root' },
  withState({
    account: null as Account | null,
    initialized: false,
    sessionVersion: 0,
    loading: false,
    error: '',
    errorCode: '',
  }),
  withMethods(
    (store, api = inject(IdentityApi), context = inject(WorkspaceContext)) => {
      let pending: Observable<Account | null> | undefined;
      let revision = 0;

      function setAccount(account: Account | null) {
        revision++;
        context.id.set(account?.workspaceId ?? null);
        patchState(store, {
          account,
          sessionVersion:
            store.sessionVersion() +
            (store.account()?.id !== account?.id ||
            store.account()?.workspaceId !== account?.workspaceId
              ? 1
              : 0),
          initialized: true,
          loading: false,
          error: '',
          errorCode: '',
        });
      }

      /** Shares startup/retry requests and ignores results superseded by session changes. */
      function load(): Observable<Account | null> {
        if (store.initialized()) return of(store.account());
        if (pending) return pending;
        const startedAt = revision;
        patchState(store, { loading: true, error: '', errorCode: '' });
        const request = api.current().pipe(
          tap((account) => {
            if (revision === startedAt) setAccount(account);
          }),
          catchError((error) => {
            if (revision === startedAt) {
              const denied = error?.status === 401 || error?.status === 403;
              patchState(store, {
                account: null,
                initialized: denied,
                error: error?.error?.error?.message ?? '',
                errorCode: error?.error?.error?.code ?? 'UNAVAILABLE',
              });
            }
            return of(null);
          }),
          finalize(() => {
            if (pending === request) pending = undefined;
            if (revision === startedAt) patchState(store, { loading: false });
          }),
          shareReplay({ bufferSize: 1, refCount: false }),
        );
        pending = request;
        return request;
      }

      function requireWorkspaceSelection(account = store.account()) {
        if (account)
          setAccount({
            ...account,
            workspaceId: null,
            workspaceName: null,
            accessRole: '',
          });
      }

      function restoreAccount(account: Account) {
        const current = store.account();
        if (!current) {
          setAccount(account);
          return;
        }
        const workspace =
          current.id === account.id
            ? account.workspaces?.find((w) => w.id === current.workspaceId)
            : undefined;
        if (workspace) {
          setAccount({
            ...account,
            workspaceId: workspace.id,
            workspaceName: workspace.name,
            accessRole: workspace.role,
          });
        } else {
          requireWorkspaceSelection(account);
        }
      }

      return {
        load,
        setAccount,
        restoreAccount,
        requireWorkspaceSelection,
        clear: () => setAccount(null),
      };
    },
  ),
);
