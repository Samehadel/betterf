import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { TranslocoService } from '@jsverse/transloco';
import { CapturePage } from './capture-page';
import { KnowledgeStore } from './knowledge.store';

describe('unsaved capture protection', () => {
  it('protects both browser unload and app navigation without persisting a draft', () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        KnowledgeStore,
        {
          provide: TranslocoService,
          useValue: { translate: () => 'Leave unsaved writing?' },
        },
      ],
    });
    const page = TestBed.runInInjectionContext(() => new CapturePage());
    const confirm = jest.spyOn(window, 'confirm').mockReturnValue(false);
    const storage = jest.spyOn(Storage.prototype, 'setItem');
    expect(page.canLeave()).toBe(true);
    page.update('title', 'Useful knowledge');
    expect(page.canLeave()).toBe(false);
    expect(confirm).toHaveBeenCalled();
    const event = new Event('beforeunload', { cancelable: true });
    page.beforeUnload(event as BeforeUnloadEvent);
    expect(event.defaultPrevented).toBe(true);
    expect(storage).not.toHaveBeenCalled();
    confirm.mockRestore();
    storage.mockRestore();
  });
});
