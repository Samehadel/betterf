import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { AuthStore } from '../core/auth.store';
import { Account, IdentityApi } from '../identity/identity.api';
import { WorkspaceShell } from './workspace-shell';

@Component({ template: '' })
class RoutePage {}

const original: Account = {
  id: 'person',
  fullName: 'Ada',
  email: 'ada@example.com',
  workspaceId: 'original',
  workspaceName: 'Original',
  accessRole: 'OWNER',
  workspaces: [
    { id: 'original', name: 'Original', role: 'OWNER' },
    { id: 'next', name: 'Next', role: 'MEMBER' },
  ],
};
const selected: Account = {
  ...original,
  workspaceId: 'next',
  workspaceName: 'Next',
  accessRole: 'MEMBER',
};

describe('Workspace selection with the real router', () => {
  async function setup(url: string, canLeave = true) {
    const leave = jest.fn(() => canLeave);
    const selectWorkspace = jest.fn(() => of(selected));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'workspace', component: RoutePage },
          { path: 'knowledge', component: RoutePage },
          {
            path: 'knowledge/new',
            component: RoutePage,
            canDeactivate: [leave],
          },
        ]),
        { provide: IdentityApi, useValue: { selectWorkspace } },
      ],
    }).overrideComponent(WorkspaceShell, {
      set: { template: '', imports: [] },
    });
    const auth = TestBed.inject(AuthStore);
    auth.setAccount(original);
    await RouterTestingHarness.create(url);
    const fixture = TestBed.createComponent(WorkspaceShell);
    fixture.detectChanges();
    const picker = document.createElement('select');
    picker.add(new Option('Original', 'original'));
    picker.add(new Option('Next', 'next'));
    picker.value = 'next';
    return {
      shell: fixture.componentInstance,
      auth,
      router: TestBed.inject(Router),
      selectWorkspace,
      leave,
      picker,
      event: { target: picker } as unknown as Event,
    };
  }

  it.each([false, true])(
    'selects from /workspace, including lost-membership recovery (%s)',
    async (recovering) => {
      const state = await setup('/workspace');
      if (recovering) state.auth.requireWorkspaceSelection();
      // Angular really ignores this same-URL navigation and resolves false.
      expect(await state.router.navigateByUrl('/workspace')).toBe(false);
      await state.shell.switchWorkspace(state.event);
      expect(state.selectWorkspace).toHaveBeenCalledWith('next');
      expect(state.auth.account()?.workspaceId).toBe('next');
      expect(state.router.url).toBe('/knowledge');
      expect(state.shell.switching()).toBe(false);
      expect(state.picker.value).toBe('next');
    },
  );

  it('keeps the original selection when leaving a capture is cancelled', async () => {
    const state = await setup('/knowledge/new', false);
    await state.shell.switchWorkspace(state.event);
    expect(state.leave).toHaveBeenCalledTimes(1);
    expect(state.selectWorkspace).not.toHaveBeenCalled();
    expect(state.auth.account()?.workspaceId).toBe('original');
    expect(state.router.url).toBe('/knowledge/new');
    expect(state.picker.value).toBe('original');
    expect(state.shell.switching()).toBe(false);
  });

  it('runs the leave guard before switching from another page', async () => {
    const state = await setup('/knowledge/new');
    state.selectWorkspace.mockImplementation(() => {
      expect(state.leave).toHaveBeenCalledTimes(1);
      expect(state.router.url).toBe('/workspace');
      return of(selected);
    });
    await state.shell.switchWorkspace(state.event);
    expect(state.selectWorkspace).toHaveBeenCalledTimes(1);
    expect(state.router.url).toBe('/knowledge');
  });
});
