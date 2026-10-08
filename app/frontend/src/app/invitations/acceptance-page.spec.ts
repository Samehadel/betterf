import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { AuthStore } from '../core/auth.store';
import { AcceptanceStore } from './acceptance.store';
import { AcceptancePage } from './acceptance-page';

describe('Invitation registration page', () => {
  it('requires explicit valid submission, scrubs the link and uses replacement credentials', () => {
    const id = '00000000-0000-4000-8000-000000000001';
    const token = 'a'.repeat(43);
    const fragments = new BehaviorSubject<string | null>(`${id}.${token}`);
    const store = {
      load: jest.fn(),
      accept: jest.fn(),
      submitting: signal(false),
      preview: signal(null),
      error: signal(''),
      account: signal(null),
    };
    const router = { navigate: jest.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
      providers: [
        { provide: ActivatedRoute, useValue: { fragment: fragments } },
        { provide: Router, useValue: router },
        { provide: AcceptanceStore, useValue: store },
        {
          provide: AuthStore,
          useValue: { clear: jest.fn(), setAccount: jest.fn() },
        },
      ],
    });
    const page = TestBed.runInInjectionContext(() => new AcceptancePage());
    expect(store.load).toHaveBeenCalledWith({ id, token });
    expect(store.accept).not.toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith([], expect.objectContaining({ replaceUrl: true }));
    fragments.next(null);
    page.submit();
    expect(store.accept).not.toHaveBeenCalled();
    page.form.setValue({
      fullName: 'Alice',
      professionalRole: 'OTHER',
      password: 'Memberpass!',
    });
    page.submit();
    expect(store.accept).toHaveBeenCalledWith({
      invitation: { id, token },
      fullName: 'Alice',
      professionalRole: 'OTHER',
      password: 'Memberpass!',
    });
    expect(page.form.controls.password.value).toBe('');
    const replacement = 'b'.repeat(43);
    fragments.next(`${id}.${replacement}`);
    fragments.next(null);
    expect(store.load).toHaveBeenLastCalledWith({ id, token: replacement });
    expect(page.form.controls.fullName.value).toBe('');
  });
});
