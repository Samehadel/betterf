import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { AuthStore } from '../core/auth.store';
import { InvitationLink } from './acceptance.api';
import { AcceptanceStore } from './acceptance.store';

@Component({
  selector: 'app-acceptance-page',
  imports: [ReactiveFormsModule, RouterLink, TranslocoDirective],
  templateUrl: './acceptance-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AcceptancePage {
  readonly store = inject(AcceptanceStore);
  private readonly auth = inject(AuthStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder).nonNullable;
  private link: InvitationLink | null = null;
  readonly invalidLink = signal(false);
  readonly form = this.fb.group({
    fullName: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(200)]],
    professionalRole: ['', Validators.required],
    password: [
      '',
      [
        Validators.required,
        Validators.minLength(10),
        Validators.maxLength(128),
        Validators.pattern(/^(?=[\s\S]*\p{Lu})(?=[\s\S]*[\p{P}\p{S}])[\s\S]*$/u),
      ],
    ],
  });

  constructor() {
    this.route.fragment.pipe(takeUntilDestroyed()).subscribe((fragment) => {
      if (fragment === null && this.link) return;
      const match =
        /^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.([A-Za-z0-9_-]{43})$/i.exec(
          fragment ?? '',
        );
      this.link = match ? { id: match[1], token: match[2] } : null;
      this.invalidLink.set(!this.link);
      this.form.reset();
      if (fragment)
        void this.router.navigate([], {
          relativeTo: this.route,
          fragment: undefined,
          replaceUrl: true,
        });
      if (this.link) this.load();
    });
    effect(() => {
      // Preview can end an existing browser session; discard any cached account.
      if (this.store.preview() || this.store.error()) this.auth.clear();
      const account = this.store.account();
      if (account) this.auth.setAccount(account);
    });
  }

  load() {
    if (this.link && !this.store.submitting()) this.store.load(this.link);
  }

  submit() {
    this.form.markAllAsTouched();
    if (!this.link || this.form.invalid || this.store.submitting()) return;
    this.store.accept({ invitation: this.link, ...this.form.getRawValue() });
    this.form.controls.password.reset();
  }

  invalid(field: keyof typeof this.form.controls) {
    const control = this.form.controls[field];
    return control.touched && control.invalid;
  }
}
