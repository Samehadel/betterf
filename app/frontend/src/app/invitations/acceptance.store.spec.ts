import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { AcceptanceStore } from './acceptance.store';
import { AcceptanceApi } from './acceptance.api';
import { IdentityApi } from '../identity/identity.api';

describe('Invitation acceptance state', () => {
  const link = { id: 'invitation', token: 'secret' };
  const body = {
    invitation: link,
    fullName: 'Alice',
    professionalRole: 'OTHER',
    password: 'Memberpass!',
  };
  const ready = {
    status: 'READY',
    companyName: 'Acme',
    email: 'alice@example.com',
    message: '',
  };
  let api: { preview: jest.Mock; accept: jest.Mock };
  let store: InstanceType<typeof AcceptanceStore>;
  beforeEach(() => {
    api = { preview: jest.fn().mockReturnValue(of(ready)), accept: jest.fn() };
    TestBed.configureTestingModule({
      providers: [
        AcceptanceStore,
        { provide: AcceptanceApi, useValue: api },
        {
          provide: IdentityApi,
          useValue: { roles: () => of([{ id: 'OTHER', label: 'Other' }]) },
        },
      ],
    });
    store = TestBed.inject(AcceptanceStore);
  });
  it('only previews on load and prevents duplicate submissions', () => {
    store.load(link);
    expect(store.preview()).toEqual(ready);
    expect(api.accept).not.toHaveBeenCalled();
    const response = new Subject<any>();
    api.accept.mockReturnValue(response);
    store.accept(body);
    store.accept(body);
    expect(api.accept).toHaveBeenCalledTimes(1);
    expect(store.submitting()).toBe(true);
    response.next({ email: ready.email, accessRole: 'MEMBER' });
    response.complete();
    expect(store.submitting()).toBe(false);
    expect(store.account()?.accessRole).toBe('MEMBER');
  });
  it('preserves correctable validation but hides the form when eligibility changes', () => {
    store.load(link);
    api.accept.mockReturnValue(
      throwError(() => ({
        status: 400,
        error: { error: { code: 'INVALID_ROLE', message: 'Choose a role.' } },
      })),
    );
    store.accept(body);
    expect(store.preview()?.status).toBe('READY');
    api.accept.mockReturnValue(
      throwError(() => ({
        status: 409,
        error: { error: { code: 'ACCOUNT_LIMIT', message: 'At capacity.' } },
      })),
    );
    store.accept(body);
    expect(store.preview()).toBeNull();
    expect(store.error()).toBe('At capacity.');
    store.load(link);
    expect(store.preview()?.status).toBe('READY');
  });
  it('offers reconciliation for a lost success response without claiming success or auto-retrying', () => {
    store.load(link);
    api.accept.mockReturnValue(throwError(() => ({ status: 0 })));
    store.accept(body);
    expect(store.error()).toBe('acceptance.uncertain');
    expect(store.account()).toBeNull();
    expect(api.accept).toHaveBeenCalledTimes(1);
    api.preview.mockReturnValue(of({ ...ready, status: 'INVITATION_USED' }));
    store.load(link);
    expect(store.preview()?.status).toBe('INVITATION_USED');
  });
});
