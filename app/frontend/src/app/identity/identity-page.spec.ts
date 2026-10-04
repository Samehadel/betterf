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
