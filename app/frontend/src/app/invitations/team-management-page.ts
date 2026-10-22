import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  signal,
  computed,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { AuthStore } from '../core/auth.store';
import { Invitation } from './invitation.api';
import { TeamManagementStore } from './team-management.store';

@Component({
  selector: 'app-team-management-page',
  imports: [DatePipe, RouterLink, TranslocoDirective],
  templateUrl: './team-management-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TeamManagementPage {
  readonly store = inject(TeamManagementStore);
  readonly auth = inject(AuthStore);
  readonly tab = signal<'invitations' | 'members'>('invitations');
  readonly now = signal(Date.now());
  readonly visibleInvitations = computed(() =>
    this.store
      .invitations()
      .filter(
        (i) =>
          i.status !== 'SMTP_ACCEPTED' ||
          new Date(i.expiresAt).getTime() > this.now(),
      ),
  );
  readonly authFailed = computed(
    () => !!this.auth.errorCode() && !this.auth.initialized(),
  );
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    this.load();
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  load() {
    this.auth
      .load()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.auth.account()?.accessRole === 'OWNER') this.refresh();
      });
  }

  select(tab: 'invitations' | 'members') {
    this.tab.set(tab);
    this.refresh();
  }

  refresh() {
    if (this.tab() === 'invitations') this.store.loadInvitations();
    else this.store.loadMembers();
  }

  eligible(invitation: Invitation) {
    return (
      invitation.status === 'FAILED' ||
      (invitation.status === 'SMTP_ACCEPTED' &&
        new Date(invitation.expiresAt).getTime() > this.now())
    );
  }

  cooldown(invitation: Invitation) {
    return Math.max(
      0,
      Math.ceil(
        (new Date(invitation.resendAvailableAt ?? 0).getTime() - this.now()) /
          1000,
      ),
    );
  }

  tabKey(event: KeyboardEvent) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    this.select(
      event.key === 'Home'
        ? 'invitations'
        : event.key === 'End'
          ? 'members'
          : this.tab() === 'invitations'
            ? 'members'
            : 'invitations',
    );
    document.getElementById('tab-' + this.tab())?.focus();
  }
}
