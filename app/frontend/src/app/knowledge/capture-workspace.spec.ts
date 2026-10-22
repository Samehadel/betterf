import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  provideHttpClientTesting,
  HttpTestingController,
} from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { AuthStore } from '../core/auth.store';
import { workspaceInterceptor } from '../core/workspace.interceptor';
import { Account } from '../identity/identity.api';
import { CapturePage } from './capture-page';
import { KnowledgeStore } from './knowledge.store';
import { KnowledgeApi } from './knowledge.api';

describe('Capture workspace ownership', () => {
  it('keeps a draft bound to its origin through selection loss and explicit selection of another workspace', () => {
    TestBed.configureTestingModule({
      providers: [
        KnowledgeStore,
        provideHttpClient(withInterceptors([workspaceInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate: jest.fn() } },
        {
          provide: TranslocoService,
          useValue: { translate: (key: string) => key },
        },
      ],
    });
    const auth = TestBed.inject(AuthStore);
    const account = { id: 'person', workspaceId: 'original' } as Account;
    auth.setAccount(account);
    const page = TestBed.runInInjectionContext(() => new CapturePage());
    page.content.set({
      title: 'Private draft',
      capture: 'Keep this in the original workspace.',
      template: 'Knowledge',
      details: [{ type: 'paragraph', text: 'Private context' }],
    });
    const store = TestBed.inject(KnowledgeStore);
    const publish = jest.spyOn(store, 'publish');
    auth.requireWorkspaceSelection();
    page.publish();
    auth.setAccount({ ...account, workspaceId: 'other' });
    page.publish();
    page.refine('title');
    expect(page.workspaceChanged()).toBe(true);
    expect(page.content().title).toBe('Private draft');
    expect(publish).not.toHaveBeenCalled();
    TestBed.inject(HttpTestingController).expectNone('/api/knowledge/tickets');
    TestBed.inject(HttpTestingController).verify();
  });
  it.each([false, true])(
    'does not retarget during CSRF (account change: %s)',
    (accountChanged) => {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([workspaceInterceptor])),
          provideHttpClientTesting(),
        ],
      });
      const auth = TestBed.inject(AuthStore);
      auth.setAccount({ id: 'person', workspaceId: 'original' } as Account);
      const http = TestBed.inject(HttpTestingController);
      const error = jest.fn();
      TestBed.inject(KnowledgeApi)
        .create('submission', { title: 'Private draft' } as any, 'original')
        .subscribe({ error });
      auth.setAccount({
        id: accountChanged ? 'other-person' : 'person',
        workspaceId: accountChanged ? 'original' : 'other',
      } as Account);
      http
        .expectOne('/api/auth/csrf')
        .flush({ data: { token: 'token', headerName: 'X-CSRF-TOKEN' } });
      expect(error).toHaveBeenCalledWith(
        expect.objectContaining({ status: 409 }),
      );
      http.expectNone('/api/knowledge/tickets');
      http.verify();
    },
  );
});
