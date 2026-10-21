import { IdentityApi } from './identity.api';
import { AuthStore } from '../core/auth.store';
import { signal } from '@angular/core';
import { Pending } from './identity.api';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { IdentityPage } from './identity-page';
import { IdentityStore } from './identity.store';

describe('Verification link navigation', () => {
  it('uses a replacement link on the same page without reusing the superseded credential', () => {
    const id = '00000000-0000-4000-8000-000000000001';
    const oldToken = 'a'.repeat(43);
    const newToken = 'b'.repeat(43);
    const fragments = new BehaviorSubject<string | null>(`${id}.${oldToken}`);
    const store = {
      clear: jest.fn(),
      run: jest.fn(),
      verified: signal(false),
      account: signal({ workspaceId: 'workspace-id' }),
    };
    const router = {
      navigate: jest.fn().mockResolvedValue(true),
      navigateByUrl: jest.fn().mockResolvedValue(true),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: IdentityApi, useValue: {} },
        { provide: AuthStore, useValue: {} },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap({}),
              data: { mode: 'verify' },
            },
            fragment: fragments,
          },
        },
        { provide: Router, useValue: router },
        { provide: IdentityStore, useValue: store },
      ],
    });
    const page = TestBed.runInInjectionContext(() => new IdentityPage());
    expect(store.run).toHaveBeenLastCalledWith({
      kind: 'verify',
      id,
      token: oldToken,
    });
    fragments.next(null);
    expect(store.run).toHaveBeenCalledTimes(1);

    fragments.next(`${id}.${newToken}`);
    fragments.next(null);
    expect(store.run).toHaveBeenCalledTimes(2);
    expect(store.run).toHaveBeenLastCalledWith({
      kind: 'verify',
      id,
      token: newToken,
    });
    expect(page.invalidFragment()).toBe(false);
    expect(router.navigate).toHaveBeenCalledTimes(2);

    store.run.mockClear();
    fragments.next('invalid');
    expect(store.run).not.toHaveBeenCalled();
    expect(page.invalidFragment()).toBe(true);
    store.verified.set(true);
    TestBed.tick();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/knowledge', {
      replaceUrl: true,
    });
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
        { provide: IdentityApi, useValue: {} },
        { provide: AuthStore, useValue: {} },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap({}),
              data: { mode: 'signup' },
            },
          },
        },
        { provide: Router, useValue: {} },
        { provide: IdentityStore, useValue: store },
      ],
    });
    return {
      page: TestBed.runInInjectionContext(() => new IdentityPage()),
      store,
    };
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
    expect(store.run).toHaveBeenCalledWith({
      kind: 'resend',
      email: saved.email,
    });
  });

  it('allows entering an address for recovery before a registration is loaded', () => {
    const { page, store } = createPage();
    page.resendForm.controls.email.setValue('existing@example.com');
    page.resend();
    expect(store.run).toHaveBeenCalledWith({
      kind: 'resend',
      email: 'existing@example.com',
    });
  });

  it('does not issue another request while delivery is queued or during cooldown', () => {
    const { page, store } = createPage({ ...saved, deliveryStatus: 'PENDING' });
    page.resend();
    expect(store.run).not.toHaveBeenCalled();
    store.pending.set({ ...saved, resendAvailableAt: '2099-01-01T00:00:00Z' });
    page.resend();
    expect(store.run).not.toHaveBeenCalled();
  });

  it.each(['', 'Differentpass!'])(
    'blocks signup with missing or mismatched confirmation (%s)',
    (confirmation) => {
      const { page, store } = createPage();
      page.registration.patchValue({
        fullName: 'Ada',
        email: 'ada@example.com',
        password: 'Validpass!',
        confirmPassword: confirmation,
      });
      page.register();
      expect(store.run).not.toHaveBeenCalled();
      expect(page.invalid('confirmPassword')).toBe(true);
      expect(page.fieldError('confirmPassword')).toBe(
        confirmation
          ? 'identity.passwordMismatch'
          : 'identity.errors.confirmPassword',
      );
    },
  );
  it('revalidates confirmation when the original password changes', () => {
    const { page } = createPage();
    page.registration.patchValue({
      fullName: 'Ada',
      email: 'ada@example.com',
      password: 'Validpass!',
      confirmPassword: 'Validpass!',
    });
    expect(page.registration.valid).toBe(true);
    page.registration.controls.password.setValue('Changedpass!');
    expect(page.registration.hasError('passwordMismatch')).toBe(true);
    page.registration.controls.confirmPassword.setValue('Changedpass!');
    expect(page.registration.valid).toBe(true);
  });
  it('shows actionable errors for missing name and invalid email/password', () => {
    const { page, store } = createPage();
    page.registration.controls.email.setValue('invalid');
    page.registration.controls.password.setValue('short');
    page.register();
    expect(page.invalid('fullName')).toBe(true);
    expect(page.fieldError('fullName')).toBe('identity.errors.fullName');
    expect(page.fieldError('email')).toBe('identity.emailError');
    expect(page.fieldError('password')).toBe('identity.passwordHint');
    expect(store.run).not.toHaveBeenCalled();
  });
  it.each([
    ['Abcdefgh!', false],
    ['abcdefghij!', false],
    ['Abcdefghij', false],
    ['Abcdefghi ', false],
    ['Abcdefghéé', false],
    ['A!' + 'a'.repeat(127), false],
    ['Abcdefghi!', true],
    ['A!' + 'a'.repeat(126), true],
    ['Éabcdefg!h', true],
    ['Abcdefgh€i', true],
  ])(
    'validates the registration password %s consistently with the API',
    (value, valid) => {
      const { page } = createPage();
      page.registration.controls.password.setValue(value);
      expect(page.registration.controls.password.valid).toBe(valid);
    },
  );
});

describe('Individual signup', () => {
  it('submits only personal fields without loading workspace roles', () => {
    const store = {
      clear: jest.fn(),
      run: jest.fn(),
      loadRoles: jest.fn(),
      busy: signal(false),
      pending: signal(null),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: IdentityApi, useValue: {} },
        { provide: AuthStore, useValue: {} },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              data: { mode: 'signup' },
              queryParamMap: convertToParamMap({}),
            },
          },
        },
        { provide: Router, useValue: {} },
        { provide: IdentityStore, useValue: store },
      ],
    });
    const page = TestBed.runInInjectionContext(() => new IdentityPage());
    page.registration.patchValue({
      fullName: 'Personal User',
      email: 'person@example.com',
      password: 'Validpass!',
      confirmPassword: 'Validpass!',
    });
    expect(page.registration.valid).toBe(true);
    page.register();
    expect(store.loadRoles).not.toHaveBeenCalled();
    expect(store.run).toHaveBeenCalledWith({
      kind: 'register',
      body: {
        fullName: 'Personal User',
        email: 'person@example.com',
        password: 'Validpass!',
      },
    });
    expect(page.registration.controls.password.value).toBe('');
    expect(page.registration.controls.confirmPassword.value).toBe('');
  });
});

describe('Trusted reauthentication for linking', () => {
  it('keeps the login form open for an existing session until password authentication succeeds', () => {
    const store = {
      clear: jest.fn(),
      account: signal({ workspaceId: null }),
      loginSucceeded: signal(false),
      pending: signal(null),
      busy: signal(false),
    };
    const router = { navigateByUrl: jest.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: IdentityApi, useValue: {} },
        { provide: AuthStore, useValue: {} },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              data: { mode: 'login' },
              queryParamMap: convertToParamMap({ social: 'LINK_REQUIRED' }),
            },
          },
        },
        { provide: Router, useValue: router },
        { provide: IdentityStore, useValue: store },
      ],
    });
    TestBed.runInInjectionContext(() => new IdentityPage());
    TestBed.tick();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    store.loginSucceeded.set(true);
    TestBed.tick();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/link-account');
  });
});

describe('Automatic workspace and rename settings', () => {
  it('renames the assigned workspace without creating another workspace or redirecting', async () => {
    const account = signal({
      id: 'person',
      fullName: 'Ada',
      workspaceId: 'default',
      workspaceName: 'My workspace',
      accessRole: 'OWNER',
    });
    const api = {
      renameWorkspace: jest.fn(() =>
        of({ ...account(), workspaceName: 'My notes' }),
      ),
      createWorkspace: jest.fn(),
    };
    const auth = { setAccount: jest.fn() };
    const router = { navigateByUrl: jest.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: IdentityApi, useValue: api },
        { provide: AuthStore, useValue: auth },
        {
          provide: IdentityStore,
          useValue: {
            account,
            clear: jest.fn(),
            run: jest.fn(),
            signedOut: signal(false),
          },
        },
        { provide: Router, useValue: router },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              data: { mode: 'workspace' },
              queryParamMap: convertToParamMap({}),
            },
          },
        },
      ],
    });
    const page = TestBed.runInInjectionContext(() => new IdentityPage());
    page.workspaceForm.controls.name.setValue('My notes');
    await page.renameWorkspace();
    expect(api.renameWorkspace).toHaveBeenCalledWith('default', 'My notes');
    expect(api.createWorkspace).not.toHaveBeenCalled();
    expect(auth.setAccount).toHaveBeenCalledWith(
      expect.objectContaining({
        workspaceId: 'default',
        workspaceName: 'My notes',
      }),
    );
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
