import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, pipe, tap } from 'rxjs';
import { ActiveMember, Invitation, InvitationApi } from './invitation.api';

export const TeamManagementStore = signalStore(
  withState({
    invitations: [] as Invitation[],
    members: [] as ActiveMember[],
    invitationsLoading: false,
    membersLoading: false,
    invitationsError: '',
    membersError: '',
    busyId: '',
    feedback: '',
    feedbackFailed: false,
  }),
  withMethods((store, api = inject(InvitationApi)) => {
    const errorMessage = (error: any) =>
      error?.error?.error?.message ?? 'team.loadFailed';
    const loadInvitations = rxMethod<void>(
      pipe(
        exhaustMap(() => {
          patchState(store, { invitationsLoading: true, invitationsError: '' });
          return api.sent().pipe(
            tap((invitations) =>
              patchState(store, { invitations, invitationsLoading: false }),
            ),
            catchError((error) => {
              patchState(store, {
                invitationsLoading: false,
                invitationsError: errorMessage(error),
              });
              return EMPTY;
            }),
          );
        }),
      ),
    );
    const loadMembers = rxMethod<void>(
      pipe(
        exhaustMap(() => {
          patchState(store, { membersLoading: true, membersError: '' });
          return api.members().pipe(
            tap((members) =>
              patchState(store, { members, membersLoading: false }),
            ),
            catchError((error) => {
              patchState(store, {
                membersLoading: false,
                membersError: errorMessage(error),
              });
              return EMPTY;
            }),
          );
        }),
      ),
    );
    const resend = rxMethod<string>(
      pipe(
        exhaustMap((id) => {
          patchState(store, {
            busyId: id,
            feedback: '',
            feedbackFailed: false,
          });
          return api.resend(id).pipe(
            tap((result) => {
              patchState(store, {
                busyId: '',
                feedback: result.message,
                feedbackFailed: !!result.deliveryFailed,
                invitations: store
                  .invitations()
                  .map((i) => (i.id === id ? result : i)),
              });
              loadInvitations();
            }),
            catchError((error) => {
              patchState(store, {
                busyId: '',
                feedback:
                  error?.status === 0 || error?.status >= 500
                    ? 'team.uncertain'
                    : errorMessage(error),
                feedbackFailed: true,
              });
              loadInvitations();
              return EMPTY;
            }),
          );
        }),
      ),
    );
    return { loadInvitations, loadMembers, resend };
  }),
);
