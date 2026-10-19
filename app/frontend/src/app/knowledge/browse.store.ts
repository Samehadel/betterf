import { effect, inject } from '@angular/core';
import {
  patchState,
  signalStore,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, pipe, switchMap, tap } from 'rxjs';
import { AuthStore } from '../core/auth.store';
import { KnowledgeApi } from './knowledge.api';
import { Ticket, TicketSummary } from './knowledge.models';

const initialState = {
  items: [] as TicketSummary[],
  detailsType: '',
  nextCursor: null as string | null,
  initialized: false,
  loading: false,
  error: false,
  scroll: 0,
  sessionVersion: -1,
};
/** Session-lived browsing state. Membership is revalidated by the route guard on every entry. */
export const BrowseStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withMethods((store, api = inject(KnowledgeApi), auth = inject(AuthStore)) => {
    let generation = 0;
    let retryAppend = false;
    function clear() {
      generation++;
      patchState(store, {
        ...initialState,
        sessionVersion: auth.sessionVersion(),
      });
    }
    function ensureSession() {
      if (store.sessionVersion() !== auth.sessionVersion()) clear();
    }
    const request = rxMethod<{ append: boolean; generation: number }>(
      pipe(
        switchMap((operation) => {
          retryAppend = operation.append;
          const version = auth.sessionVersion();
          patchState(store, { loading: true, error: false });
          return api
            .browse(
              store.detailsType(),
              operation.append ? store.nextCursor() : null,
            )
            .pipe(
              tap((page) => {
                if (
                  operation.generation !== generation ||
                  version !== auth.sessionVersion()
                )
                  return;
                patchState(store, {
                  items: operation.append
                    ? [...store.items(), ...page.items]
                    : page.items,
                  nextCursor: page.nextCursor,
                  initialized: true,
                  loading: false,
                });
              }),
              catchError((error) => {
                if (
                  operation.generation !== generation ||
                  version !== auth.sessionVersion()
                )
                  return EMPTY;
                if ([401, 403, 404].includes(error.status)) clear();
                patchState(store, { error: true, loading: false });
                return EMPTY;
              }),
            );
        }),
      ),
    );
    function refresh(type = store.detailsType()) {
      ensureSession();
      generation++;
      const changed = type !== store.detailsType();
      patchState(store, {
        detailsType: type,
        scroll: 0,
        ...(changed ? { items: [], nextCursor: null, initialized: false } : {}),
      });
      request({ append: false, generation });
    }
    return {
      clear,
      ensureSession,
      refresh,
      enter() {
        ensureSession();
        if (!store.initialized() && !store.loading()) refresh();
      },
      retry() {
        if (!store.loading()) request({ append: retryAppend, generation });
      },
      more() {
        if (store.loading()) return;
        request({ append: store.initialized(), generation });
      },
      rememberScroll(scroll: number) {
        patchState(store, { scroll });
      },
      updateTicket(ticket: Ticket) {
        ensureSession();
        patchState(store, {
          items: store.items().map((item) =>
            item.id === ticket.id
              ? {
                  ...item,
                  classificationStatus: ticket.classificationStatus,
                  attributes: ticket.attributes,
                }
              : item,
          ),
        });
      },
    };
  }),
  withHooks({
    onInit(store) {
      const auth = inject(AuthStore);
      effect(() => {
        auth.sessionVersion();
        store.ensureSession();
      });
    },
  }),
);
