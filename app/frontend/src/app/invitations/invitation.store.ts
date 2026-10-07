import { inject } from '@angular/core';
import { patchState, signalStore, withState, withMethods } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, mergeMap, pipe, tap } from 'rxjs';
import { Invitation, InvitationApi } from './invitation.api';
export interface InvitationRow {
  id: number;
  email: string;
  state: 'editable' | 'sending' | 'sent' | 'error' | 'pending' | 'uncertain';
  message: string;
}
export const InvitationStore = signalStore(
  withState({
    history: [] as Invitation[],
    historyLoading: false,
    historyError: false,
    historyPage: 0,
    historyHasMore: false,
    rows: [
      { id: 1, email: '', state: 'editable', message: '' },
    ] as InvitationRow[],
  }),
  withMethods((store, api = inject(InvitationApi)) => {
    const update = (id: number, change: Partial<InvitationRow>) =>
      patchState(store, {
        rows: store
          .rows()
          .map((row) => (row.id === id ? { ...row, ...change } : row)),
      });
    const run = rxMethod<{ id: number; status?: boolean }>(
      pipe(
        mergeMap((action) => {
          const row = store.rows().find((r) => r.id === action.id);
          if (!row || row.state === 'sent' || row.state === 'sending')
            return EMPTY;
          update(row.id, { state: 'sending', message: '' });
          return api.request(row.email.trim(), action.status).pipe(
            tap((result) =>
              update(row.id, {
                state:
                  result.status === 'SMTP_ACCEPTED'
                    ? 'sent'
                    : result.status === 'FAILED'
                      ? 'error'
                      : 'uncertain',
                message: result.message,
              }),
            ),
            catchError((error) => {
              const code = error?.error?.error?.code;
              const known =
                error?.status >= 400 &&
                error?.status < 500 &&
                error?.status !== 408;
              update(row.id, {
                state:
                  code === 'INVITATION_PENDING'
                    ? 'pending'
                    : known
                      ? 'error'
                      : 'uncertain',
                message: known
                  ? (error?.error?.error?.message ?? 'invitations.failed')
                  : 'invitations.uncertain',
              });
              return EMPTY;
            }),
          );
        }),
      ),
    );
    const loadHistory = rxMethod<void>(
      pipe(
        exhaustMap(() => {
          patchState(store, { historyLoading: true, historyError: false });
          return api.history(store.historyPage()).pipe(
            tap((result) => {
              const records = new Map(
                store.history().map((record) => [record.id, record]),
              );
              for (const record of result.invitations)
                records.set(record.id, record);
              patchState(store, {
                history: [...records.values()],
                historyPage: store.historyPage() + 1,
                historyHasMore: result.hasMore,
                historyLoading: false,
              });
            }),
            catchError(() => {
              patchState(store, { historyLoading: false, historyError: true });
              return EMPTY;
            }),
          );
        }),
      ),
    );

    return {
      loadHistory,
      updateEmail(id: number, email: string) {
        const row = store.rows().find((r) => r.id === id);
        if (row && !['sent', 'sending', 'uncertain'].includes(row.state))
          update(id, { email, state: 'editable', message: '' });
      },
      invalid(id: number) {
        update(id, { state: 'error', message: 'invitations.invalid' });
      },
      send(id: number) {
        run({ id });
      },
      check(id: number) {
        run({ id, status: true });
      },
      add(id: number) {
        const rows = store.rows();
        if (rows.at(-1)?.id !== id || rows.at(-1)?.state !== 'sent') return;
        patchState(store, {
          rows: [
            ...rows,
            { id: id + 1, email: '', state: 'editable', message: '' },
          ],
        });
      },
    };
  }),
);
