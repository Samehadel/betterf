import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  HostListener,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
} from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { filter, map } from 'rxjs';
import { AuthStore } from '../core/auth.store';
import { IdentityStore } from '../identity/identity.store';

/** Keeps workspace navigation available while feature pages own their content and permissions. */
@Component({
  selector: 'app-workspace-shell',
  imports: [RouterLink, RouterLinkActive, TranslocoDirective],
  providers: [IdentityStore],
  templateUrl: './workspace-shell.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkspaceShell {
  readonly auth = inject(AuthStore);
  readonly session = inject(IdentityStore);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly sidebar = viewChild<ElementRef<HTMLElement>>('sidebar');
  private readonly menuButton =
    viewChild<ElementRef<HTMLButtonElement>>('menuButton');
  readonly collapsed = signal(false);
  readonly mobileOpen = signal(false);
  readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects.split(/[?#]/)[0]),
    ),
    { initialValue: this.router.url.split(/[?#]/)[0] },
  );
  readonly active = computed(() => {
    const url = this.url();
    return (
      !!this.auth.account() &&
      (url === '/company' ||
        url.startsWith('/company/') ||
        url === '/knowledge' ||
        url.startsWith('/knowledge/'))
    );
  });
  readonly section = computed(() => {
    if (this.url() === '/knowledge' || this.url().startsWith('/knowledge/'))
      return 'workspace.knowledge';
    if (this.url() === '/company/invitations') return 'invitations.formTitle';
    if (this.url().startsWith('/company/team')) return 'workspace.team';
    return 'workspace.home';
  });
  readonly initials = computed(
    () =>
      this.auth
        .account()
        ?.fullName.trim()
        .split(/\s+/)
        .slice(0, 2)
        .map((part) => part[0])
        .join('')
        .toUpperCase() ?? '',
  );

  constructor() {
    effect(() => {
      this.url();
      this.mobileOpen.set(false);
    });
    effect(() => {
      if (this.session.signedOut()) {
        this.mobileOpen.set(false);
        void this.router.navigateByUrl('/login');
      }
    });
  }

  openMobile() {
    this.mobileOpen.set(true);
    afterNextRender(
      () => {
        this.sidebar()
          ?.nativeElement.querySelector<HTMLButtonElement>('.mobile-close')
          ?.focus();
      },
      { injector: this.injector },
    );
  }

  closeMobile() {
    if (!this.mobileOpen()) return;
    this.mobileOpen.set(false);
    afterNextRender(() => this.menuButton()?.nativeElement.focus(), {
      injector: this.injector,
    });
  }

  @HostListener('window:resize')
  closeDrawerOnDesktop() {
    if (window.innerWidth > 650) this.mobileOpen.set(false);
  }

  /** Keeps keyboard focus in the open mobile drawer until it is dismissed. */
  navigationKey(event: KeyboardEvent) {
    if (!this.mobileOpen()) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      this.closeMobile();
      return;
    }
    if (event.key !== 'Tab') return;
    const controls = Array.from(
      this.sidebar()?.nativeElement.querySelectorAll<HTMLElement>(
        'a, button:not(:disabled), summary',
      ) ?? [],
    ).filter((element) => element.getClientRects().length > 0);
    const first = controls[0];
    const last = controls[controls.length - 1];
    if (event.shiftKey && event.target === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && event.target === last) {
      event.preventDefault();
      first?.focus();
    }
  }
}
