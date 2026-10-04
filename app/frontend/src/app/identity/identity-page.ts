import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { IdentityStore } from './identity.store';
@Component({
  selector: 'app-identity-page',
  imports: [ReactiveFormsModule, RouterLink, TranslocoDirective],
  templateUrl: './identity-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IdentityPage {
  readonly store = inject(IdentityStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly mode = this.route.snapshot.data['mode'] as 'register' | 'verify' | 'login' | 'company';
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly registration = this.fb.group({
    companyName: ['', [Validators.required, Validators.maxLength(200)]],
    website: ['', [Validators.required, Validators.maxLength(253)]],
    specialization: ['', [Validators.required, Validators.maxLength(200)]],
    fullName: ['', [Validators.required, Validators.maxLength(200)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.minLength(15), Validators.maxLength(128)]],
    professionalRole: ['', Validators.required],
  });
  readonly loginForm = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  readonly resendForm = this.fb.group({ email: ['', [Validators.required, Validators.email]] });
  readonly submitted = signal(false);
  readonly now = signal(Date.now());
  readonly remaining = computed(
    () =>
      Math.max(
        0,
        Math.ceil((Date.parse(this.store.pending()?.resendAvailableAt ?? '') - this.now()) / 1000),
      ) || 0,
  );
  readonly deliveryMessage = computed(() => {
    switch (this.store.pending()?.deliveryStatus) {
      case 'SMTP_ACCEPTED':
        return 'identity.pending';
      case 'FAILED':
        return 'identity.failedDelivery';
      case 'CANCELLED':
        return 'identity.deliveryCancelled';
      default:
        return 'identity.queued';
    }
  });
  readonly deliveryInProgress = computed(() =>
    ['PENDING', 'SENDING', 'FAILED'].includes(this.store.pending()?.deliveryStatus ?? ''),
  );
  private verification: { id: string; token: string } | null = null;
  readonly invalidFragment = signal(false);
  constructor() {
    this.store.clear();
    if (this.mode === 'register') this.store.loadRoles();
    if (this.mode === 'company') this.store.run({ kind: 'current' });
    if (this.mode === 'verify') {
      this.route.fragment.pipe(takeUntilDestroyed()).subscribe((fragment) => {
        // Removing the credential from history must not discard the in-memory link.
        if (fragment === null) {
          if (!this.verification) this.invalidFragment.set(true);
          return;
        }

        this.store.clear();
        const match =
          /^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.([A-Za-z0-9_-]{43})$/i.exec(
            fragment,
          );
        this.verification = match ? { id: match[1], token: match[2] } : null;
        this.invalidFragment.set(!match);

        // Observe replacement links even when Angular reuses this page for a fragment navigation.
        // Do not verify on GET: mail scanners must not consume the link.
        void this.router.navigate([], {
          relativeTo: this.route,
          fragment: undefined,
          replaceUrl: true,
        });
      });
    }
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    const deliveryTimer = setInterval(() => {
      const pending = this.store.pending();
      if (pending && this.deliveryInProgress()) this.store.refreshDelivery(pending.email);
    }, 5000);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      clearInterval(deliveryTimer);
    });
    effect(() => {
      if (this.mode === 'login' && this.store.account()) void this.router.navigateByUrl('/company');
      if (this.mode === 'company' && this.store.signedOut())
        void this.router.navigateByUrl('/login');
    });
  }
  register() {
    this.submitted.set(true);
    this.registration.markAllAsTouched();
    if (this.registration.invalid) return;
    const body = this.registration.getRawValue();
    this.resendForm.controls.email.setValue(body.email);
    this.store.run({ kind: 'register', body });
    this.registration.controls.password.reset();
  }
  login() {
    this.submitted.set(true);
    this.loginForm.markAllAsTouched();
    if (this.loginForm.invalid) return;
    const { email, password } = this.loginForm.getRawValue();
    this.store.run({ kind: 'login', email, password });
    this.loginForm.controls.password.reset();
  }
  resend() {
    if (this.store.busy() || this.remaining() > 0 || this.deliveryInProgress()) return;
    const pending = this.store.pending();
    if (pending) {
      this.store.run({ kind: 'resend', email: pending.email });
      return;
    }
    this.resendForm.markAllAsTouched();
    if (this.resendForm.valid)
      this.store.run({ kind: 'resend', email: this.resendForm.getRawValue().email });
  }
  verify() {
    if (this.verification) this.store.run({ kind: 'verify', ...this.verification });
  }
  invalid(field: keyof typeof this.registration.controls) {
    const control = this.registration.controls[field];
    return control.invalid && control.touched;
  }
  fieldError(field: keyof typeof this.registration.controls) {
    const control = this.registration.controls[field];
    if (control.hasError('required')) return 'identity.errors.' + field;
    if (field === 'email') return 'identity.emailError';
    if (field === 'password') return 'identity.passwordHint';
    return 'identity.tooLong';
  }
}
