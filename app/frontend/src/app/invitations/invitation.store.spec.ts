import { Subject } from 'rxjs';
import { InvitationApi, Invitation } from './invitation.api';
import { InvitationStore } from './invitation.store';
import { TestBed } from '@angular/core/testing';
describe('Invitation flow', () => {
  let store: InstanceType<typeof InvitationStore>;
  let response: Subject<Invitation>;
  let api: { request: jest.Mock; history: jest.Mock };
  beforeEach(() => {
    response = new Subject<Invitation>();
    api = { request: jest.fn(() => response), history: jest.fn() };
    TestBed.configureTestingModule({
      providers: [InvitationStore, { provide: InvitationApi, useValue: api }],
    });
    store = TestBed.inject(InvitationStore);
    store.updateEmail(1, 'colleague@gmail.com');
  });
  const accepted: Invitation = {
    id: 'id',
    email: 'colleague@gmail.com',
    status: 'SMTP_ACCEPTED',
    expiresAt: '2026-10-14T08:00:00Z',
    message: 'Invitation sent.',
  };
  it('prevents repeat submissions, locks success, and preserves earlier results when adding a row', () => {
    store.send(1);
    store.send(1);
    expect(api.request).toHaveBeenCalledTimes(1);
    expect(store.rows()[0].state).toBe('sending');
    response.next(accepted);
    response.complete();
    store.updateEmail(1, 'changed@example.com');
    store.send(1);
    store.add(1);
    store.add(1);
    expect(store.rows()).toHaveLength(2);
    expect(store.rows()[0]).toMatchObject({
      email: 'colleague@gmail.com',
      state: 'sent',
      message: 'Invitation sent.',
    });
    expect(store.rows()[1].state).toBe('editable');
    expect(api.request).toHaveBeenCalledTimes(1);
  });
  it('keeps failed delivery editable and retryable without adding another row', () => {
    store.send(1);
    response.next({
      ...accepted,
      status: 'FAILED',
      message: 'Delivery failed.',
    });
    response.complete();
    expect(store.rows()[0].state).toBe('error');
    store.add(1);
    expect(store.rows()).toHaveLength(1);
    store.updateEmail(1, 'corrected@gmail.com');
    response = new Subject();
    store.send(1);
    expect(api.request).toHaveBeenLastCalledWith(
      'corrected@gmail.com',
      undefined,
    );
    response.next(accepted);
    expect(store.rows()[0].state).toBe('sent');
  });
  it('shows pending feedback as a distinct outcome and permits correcting the address', () => {
    store.send(1);
    response.error({
      status: 409,
      error: {
        error: {
          code: 'INVITATION_PENDING',
          message: 'Invitation already pending',
        },
      },
    });
    expect(store.rows()[0]).toMatchObject({
      state: 'pending',
      message: 'Invitation already pending',
    });
    store.add(1);
    expect(store.rows()).toHaveLength(1);
    store.updateEmail(1, 'another@gmail.com');
    expect(store.rows()[0].state).toBe('editable');
  });
  it('reconciles an uncertain response through status without another send', () => {
    store.send(1);
    response.error(new Error('timeout'));
    expect(store.rows()[0].state).toBe('uncertain');
    store.updateEmail(1, 'changed@example.com');
    expect(store.rows()[0].email).toBe('colleague@gmail.com');
    response = new Subject();
    store.check(1);
    expect(api.request).toHaveBeenLastCalledWith('colleague@gmail.com', true);
    response.next(accepted);
    expect(store.rows()[0].state).toBe('sent');
  });
  it('leaves validation failures editable and reports configured capacity feedback', () => {
    store.invalid(1);
    expect(store.rows()[0].state).toBe('error');
    expect(api.request).not.toHaveBeenCalled();
    store.send(1);
    response.error({
      status: 409,
      error: {
        error: {
          code: 'ACCOUNT_LIMIT',
          message:
            'Your organization has reached its limit of 3 active accounts. You cannot send invitations.',
        },
      },
    });
    expect(store.rows()[0].message).toContain('limit of 3');
    expect(store.rows()[0].state).toBe('error');
  });
  it('restores previous invitations from the API without resending or changing the new-entry row', () => {
    const history = new Subject<{
      invitations: Invitation[];
      hasMore: boolean;
    }>();
    api.history.mockReturnValue(history);
    store.loadHistory();
    expect(store.historyLoading()).toBe(true);
    history.next({ invitations: [accepted], hasMore: false });
    expect(store.history()).toEqual([accepted]);
    expect(store.rows()).toHaveLength(1);
    expect(store.rows()[0].state).toBe('editable');
    expect(api.request).not.toHaveBeenCalled();
  });

  it('loads older records without overlapping requests and keeps history when a page fails', () => {
    let history = new Subject<{
      invitations: Invitation[];
      hasMore: boolean;
    }>();
    api.history.mockImplementation(() => history);
    store.loadHistory();
    store.loadHistory();
    expect(api.history).toHaveBeenCalledTimes(1);
    history.next({ invitations: [accepted], hasMore: true });
    history.complete();
    history = new Subject();
    store.loadHistory();
    expect(api.history).toHaveBeenLastCalledWith(1);
    history.error(new Error('Network unavailable'));
    expect(store.history()).toEqual([accepted]);
    expect(store.historyError()).toBe(true);
    expect(store.historyLoading()).toBe(false);
    history = new Subject();
    store.loadHistory();
    history.next({
      invitations: [{ ...accepted, id: 'older', email: 'older@gmail.com' }],
      hasMore: false,
    });
    expect(store.history()).toHaveLength(2);
    expect(store.historyError()).toBe(false);
    expect(store.historyHasMore()).toBe(false);
  });
});
