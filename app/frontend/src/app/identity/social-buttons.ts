import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { IdentityApi } from './identity.api';

@Component({
  selector: 'app-social-buttons',
  imports: [TranslocoDirective],
  templateUrl: './social-buttons.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SocialButtons {
  readonly providers = toSignal(
    inject(IdentityApi)
      .providers()
      .pipe(catchError(() => of(null))),
  );
}
