import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { BrowseStore } from './browse.store';
import { AuthStore } from '../core/auth.store';
import { Account } from '../identity/identity.api';
import { TicketSummary } from './knowledge.models';

const account = { id: 'author', organizationId: 'org' } as Account;
const summary = {
  id: 'one',
  title: 'A useful insight',
  detailsType: 'Coding',
  attributes: [],
} as unknown as TicketSummary;
describe('Organization knowledge browsing session', () => {
  let store: InstanceType<typeof BrowseStore>;
  let auth: InstanceType<typeof AuthStore>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthStore);
    auth.setAccount(account);
    store = TestBed.inject(BrowseStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function firstPage() {
    store.enter();
    http
      .expectOne('/api/knowledge/tickets')
      .flush({ data: { items: [summary], nextCursor: 'next' } });
  }
  it('restores loaded entries, filter, cursor and scroll, retaining entries on append failure', () => {
    firstPage();
    store.rememberScroll(700);
    store.enter();
    http.expectNone('/api/knowledge/tickets');
    expect(store.scroll()).toBe(700);
    store.more();
    http
      .expectOne('/api/knowledge/tickets?cursor=next')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    expect(store.items()).toEqual([summary]);
    expect(store.error()).toBe(true);
    store.more();
    http.expectOne('/api/knowledge/tickets?cursor=next').flush({
      data: { items: [{ ...summary, id: 'two' }], nextCursor: null },
    });
    expect(store.items().map((item) => item.id)).toEqual(['one', 'two']);
    expect(store.nextCursor()).toBeNull();
  });
  it('retains loaded entries after a failed refresh and retries from the first page', () => {
    firstPage();
    store.refresh();
    http
      .expectOne('/api/knowledge/tickets')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    expect(store.items()).toEqual([summary]);
    store.retry();
    http
      .expectOne('/api/knowledge/tickets')
      .flush({
        data: { items: [{ ...summary, id: 'newest' }], nextCursor: null },
      });
    expect(store.items().map((item) => item.id)).toEqual(['newest']);
  });
  it('cancels stale responses on filter changes and refresh retains the selection', () => {
    store.enter();
    const old = http.expectOne('/api/knowledge/tickets');
    store.refresh('Coding');
    expect(old.cancelled).toBe(true);
    http
      .expectOne('/api/knowledge/tickets?detailsType=Coding')
      .flush({ data: { items: [summary], nextCursor: null } });
    store.rememberScroll(900);
    store.refresh();
    expect(store.items()).toEqual([summary]);
    expect(store.scroll()).toBe(0);
    http
      .expectOne('/api/knowledge/tickets?detailsType=Coding')
      .flush({ data: { items: [], nextCursor: null } });
    expect(store.detailsType()).toBe('Coding');
    expect(store.initialized()).toBe(true);
  });
  it.each([401, 403, 404])(
    'clears content on denied collection reads (%s)',
    (status) => {
      firstPage();
      store.more();
      http
        .expectOne('/api/knowledge/tickets?cursor=next')
        .flush({}, { status, statusText: 'Denied' });
      expect(store.items()).toEqual([]);
      expect(store.nextCursor()).toBeNull();
    },
  );
  it('clears the session on logout and organization changes and rejects old in-flight results', () => {
    firstPage();
    store.more();
    const old = http.expectOne('/api/knowledge/tickets?cursor=next');
    auth.clear();
    TestBed.tick();
    expect(store.items()).toEqual([]);
    old.flush({ data: { items: [summary], nextCursor: null } });
    expect(store.items()).toEqual([]);
    auth.setAccount({ ...account, organizationId: 'other' });
    store.enter();
    http
      .expectOne('/api/knowledge/tickets')
      .flush({ data: { items: [], nextCursor: null } });
    expect(store.detailsType()).toBe('');
    expect(store.items()).toEqual([]);
  });
});
