import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, pipe, tap } from 'rxjs';
import { KnowledgeApi } from './knowledge.api';
import {
  Refinement,
  RefinementField,
  Ticket,
  TicketContent,
} from './knowledge.models';
export const KnowledgeStore = signalStore(
  withState({
    ticket: null as Ticket | null,
    suggestion: null as Refinement | null,
    busy: false,
    uncertain: false,
    refining: false,
    loading: false,
    error: '',
    refinementError: '',
  }),
  withMethods((store, api = inject(KnowledgeApi)) => {
    const message = (e: any) =>
      e?.error?.error?.message ?? 'knowledge.unavailable';
    const publish = rxMethod<{ id: string; content: TicketContent }>(
      pipe(
        exhaustMap(({ id, content }) => {
          patchState(store, { busy: true, error: '' });
          return api.create(id, content).pipe(
            tap((ticket) =>
              patchState(store, { ticket, busy: false, uncertain: false }),
            ),
            catchError((e) => {
              patchState(store, {
                busy: false,
                uncertain:
                  !e?.status || e.status >= 500 || e.name === 'TimeoutError',
                error: message(e),
              });
              return EMPTY;
            }),
          );
        }),
      ),
    );
    const refine = rxMethod<{ content: TicketContent; field: RefinementField }>(
      pipe(
        exhaustMap(({ content, field }) => {
          patchState(store, {
            refining: true,
            refinementError: '',
            suggestion: null,
          });
          return api.refine(content, field).pipe(
            tap((suggestion) =>
              patchState(store, { suggestion, refining: false }),
            ),
            catchError((e) => {
              patchState(store, {
                refining: false,
                refinementError: message(e),
              });
              return EMPTY;
            }),
          );
        }),
      ),
    );
    const load = rxMethod<string>(
      pipe(
        exhaustMap((id) => {
          patchState(store, { loading: true, error: '' });
          return api.get(id).pipe(
            tap((ticket) => patchState(store, { ticket, loading: false })),
            catchError((e) => {
              patchState(store, {
                loading: false,
                error: message(e),
                ...([401, 403, 404].includes(e?.status)
                  ? { ticket: null }
                  : {}),
              });
              return EMPTY;
            }),
          );
        }),
      ),
    );
    return {
      publish,
      refine,
      load,
      discard: () =>
        patchState(store, { suggestion: null, refinementError: '' }),
    };
  }),
);
