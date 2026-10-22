import {
  ChangeDetectionStrategy,
  Component,
  inject,
  computed,
  DestroyRef,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { AuthStore } from '../core/auth.store';
import { InvitationStore, InvitationRow } from './invitation.store';
@Component({
  selector: 'app-invitation-page',
  imports: [FormsModule, RouterLink, TranslocoDirective],
  templateUrl: './invitation-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvitationPage {
  readonly store = inject(InvitationStore);
  private readonly auth = inject(AuthStore);
  readonly account = this.auth.account;
  readonly loading = this.auth.loading;
  readonly loadFailed = computed(
    () => !!this.auth.errorCode() && !this.auth.initialized(),
  );
  private readonly destroyRef = inject(DestroyRef);
  constructor() {
    this.load();
  }
  load() {
    this.auth.load().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
  }

  send(row: InvitationRow, valid: boolean) {
    if (valid) this.store.send(row.id);
    else this.store.invalid(row.id);
  }
  add(id: number) {
    this.store.add(id);
    setTimeout(() =>
      document.getElementById('invitation-email-' + (id + 1))?.focus(),
    );
  }
}
