import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { KnowledgeStore } from './knowledge.store';

const ticket = {
  id: 'one',
  content: { title: 'Published', details: [] },
  classificationStatus: 'PENDING',
  attributes: [],
};
describe('Published ticket refresh', () => {
  let store: InstanceType<typeof KnowledgeStore>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        KnowledgeStore,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    store = TestBed.inject(KnowledgeStore);
    http = TestBed.inject(HttpTestingController);
    store.load('one');
    http.expectOne('/api/knowledge/tickets/one').flush({ data: ticket });
  });
  afterEach(() => http.verify());
  it('keeps published content during refresh, rejects overlapping loads and retains content on failure', () => {
    store.load('one');
    store.load('one');
    expect(store.ticket()?.content.title).toBe('Published');
    http
      .expectOne('/api/knowledge/tickets/one')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    expect(store.ticket()?.content.title).toBe('Published');
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeTruthy();
    store.load('one');
    http
      .expectOne('/api/knowledge/tickets/one')
      .flush({ data: { ...ticket, classificationStatus: 'COMPLETE' } });
    expect(store.ticket()?.classificationStatus).toBe('COMPLETE');
    expect(store.error()).toBe('');
  });
  it.each([401, 403, 404])(
    'clears loaded content when access becomes unavailable (%s)',
    (status) => {
      store.load('one');
      http
        .expectOne('/api/knowledge/tickets/one')
        .flush({}, { status, statusText: 'Unavailable' });
      expect(store.ticket()).toBeNull();
    },
  );
});
