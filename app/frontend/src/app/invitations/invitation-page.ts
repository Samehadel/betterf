import { ChangeDetectionStrategy, Component, inject, signal, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { IdentityApi, Account } from '../identity/identity.api';
import { InvitationStore, InvitationRow } from './invitation.store';
@Component({selector: 'app-invitation-page', imports: [FormsModule, RouterLink, TranslocoDirective],
  templateUrl: './invitation-page.html', changeDetection: ChangeDetectionStrategy.OnPush})
export class InvitationPage {
  readonly store = inject(InvitationStore);
  readonly account = signal<Account | null>(null);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  private readonly identity = inject(IdentityApi);
  private readonly destroyRef = inject(DestroyRef);
  constructor() { this.load(); }
  load() {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.identity.current().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: account => { this.account.set(account); this.loading.set(false); },
      error: error => { this.loadFailed.set(error?.status !== 403); this.loading.set(false); },
    });
  }
  send(row: InvitationRow, valid: boolean) {
    if (valid) this.store.send(row.id); else this.store.invalid(row.id);
  }
  add(id: number) {
    this.store.add(id);
    setTimeout(() => document.getElementById('invitation-email-' + (id + 1))?.focus());
  }
}
