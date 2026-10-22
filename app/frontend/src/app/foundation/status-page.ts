import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { translateObjectSignal } from '@jsverse/transloco';
import { StatusStore } from './status.store';

@Component({
  templateUrl: './status-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusPage {
  readonly store = inject(StatusStore);
  readonly text = translateObjectSignal('foundation');
  constructor() { this.store.check(); }
}
