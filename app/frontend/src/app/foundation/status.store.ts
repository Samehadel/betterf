import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, pipe, tap } from 'rxjs';
import { StatusApi } from './status.api';

type Connection = 'loading' | 'connected' | 'unavailable';

export const StatusStore = signalStore(
  withState({ connection: 'loading' as Connection }),
  withMethods((store, api = inject(StatusApi)) => ({
    check: rxMethod<void>(pipe(
      exhaustMap(() => {
        patchState(store, { connection: 'loading' });
        return api.check().pipe(
          tap(() => patchState(store, { connection: 'connected' })),
          catchError(() => {
            patchState(store, { connection: 'unavailable' });
            return EMPTY;
          }),
        );
      }),
    )),
  })),
);
