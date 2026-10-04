import { signal } from '@angular/core';
import { Pending } from './identity.api';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { IdentityPage } from './identity-page';
import { IdentityStore } from './identity.store';

describe('Verification link navigation', () => {
  it('uses a replacement link on the same page without reusing the superseded credential', () => {
    const id = '00000000-0000-4000-8000-000000000001';
    const oldToken = 'a'.repeat(43);
    const newToken = 'b'.repeat(43);
    const fragments = new BehaviorSubject<string | null>(`${id}.${oldToken}`);
    const store = { clear: jest.fn(), run: jest.fn() };
    const router = { navigate: jest.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { data: { mode: 'verify' } }, fragment: fragments },
        },
        { provide: Router, useValue: router },
        { provide: IdentityStore, useValue: store },
      ],
    });
    const page = TestBed.runInInjectionContext(() => new IdentityPage());
    fragments.next(null);
    page.verify();
    expect(store.run).toHaveBeenLastCalledWith({ kind: 'verify', id, token: oldToken });

    fragments.next(`${id}.${newToken}`);
    fragments.next(null);
    page.verify();
    expect(store.run).toHaveBeenLastCalledWith({ kind: 'verify', id, token: newToken });
    expect(page.invalidFragment()).toBe(false);
    expect(router.navigate).toHaveBeenCalledTimes(2);

    fragments.next('invalid');
    store.run.mockClear();
    page.verify();
    expect(store.run).not.toHaveBeenCalled();
    expect(page.invalidFragment()).toBe(true);
  });
});

describe('Registration email recovery', () => {
  function createPage(delivery: Pending | null = null) {
    const pending = signal(delivery);
    const store = {
      pending,
      busy: signal(false),
      clear: jest.fn(),
      loadRoles: jest.fn(),
      run: jest.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { data: { mode: 'register' } } } },
        { provide: Router, useValue: {} },
        { provide: IdentityStore, useValue: store },
      ],
    });
    return { page: TestBed.runInInjectionContext(() => new IdentityPage()), store };
  }

  const saved: Pending = {
    email: 'saved@example.com',
    deliveryStatus: 'SMTP_ACCEPTED',
    resendAvailableAt: '2020-01-01T00:00:00Z',
  };

  it('resends to the saved email even if the recovery form contains another address', () => {
    const { page, store } = createPage(saved);
    page.resendForm.controls.email.setValue('different@example.com');
    page.resend();
    expect(store.run).toHaveBeenCalledWith({ kind: 'resend', email: saved.email });
  });

  it('allows entering an address for recovery before a registration is loaded', () => {
    const { page, store } = createPage();
    page.resendForm.controls.email.setValue('existing@example.com');
    page.resend();
    expect(store.run).toHaveBeenCalledWith({ kind: 'resend', email: 'existing@example.com' });
  });

  it('does not issue another request while delivery is queued or during cooldown', () => {
    const { page, store } = createPage({ ...saved, deliveryStatus: 'PENDING' });
    page.resend();
    expect(store.run).not.toHaveBeenCalled();
    store.pending.set({ ...saved, resendAvailableAt: '2099-01-01T00:00:00Z' });
    page.resend();
    expect(store.run).not.toHaveBeenCalled();
  });

  it('shows actionable errors for missing website and invalid email/password', () => {
    const { page, store } = createPage();
    page.registration.controls.email.setValue('invalid');
    page.registration.controls.password.setValue('short');
    page.register();
    expect(page.invalid('website')).toBe(true);
    expect(page.fieldError('website')).toBe('identity.errors.website');
    expect(page.fieldError('email')).toBe('identity.emailError');
    expect(page.fieldError('password')).toBe('identity.passwordHint');
    expect(store.run).not.toHaveBeenCalled();
  });
});
