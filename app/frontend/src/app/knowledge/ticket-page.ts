import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  untracked,
  inject,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { KnowledgeStore } from './knowledge.store';
import { DetailsView } from './details-view';
import { BrowseStore } from './browse.store';
import { attributeLabels, fullDate } from './knowledge.models';
@Component({
  providers: [KnowledgeStore],
  selector: 'app-ticket-page',
  imports: [RouterLink, TranslocoDirective, DetailsView],
  templateUrl: './ticket-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TicketPage {
  readonly store = inject(KnowledgeStore);
  readonly labels = attributeLabels;
  readonly fullDate = fullDate;
  readonly browse = inject(BrowseStore);
  readonly created = history.state?.created === true;
  readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id')!;
  constructor() {
    this.store.load(this.id);
    effect(() => {
      const ticket = this.store.ticket();
      if (ticket) untracked(() => this.browse.updateTicket(ticket));
    });
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
