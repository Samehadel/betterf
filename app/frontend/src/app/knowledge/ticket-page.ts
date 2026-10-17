import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { KnowledgeStore } from './knowledge.store';
import { DetailsView } from './details-view';
@Component({
  providers: [KnowledgeStore],
  selector: 'app-ticket-page',
  imports: [DatePipe, RouterLink, TranslocoDirective, DetailsView],
  templateUrl: './ticket-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TicketPage {
  readonly store = inject(KnowledgeStore);
  readonly created = history.state?.created === true;
  readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  constructor() {
    this.store.load(this.id);
    const timer = setInterval(() => {
      if (
        ['PENDING', 'PROCESSING', 'RETRYING'].includes(
          this.store.ticket()?.classificationStatus ?? '',
        )
      )
        this.store.load(this.id);
    }, 5000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
}
