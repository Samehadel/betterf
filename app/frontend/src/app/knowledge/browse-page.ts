import { AuthStore } from '../core/auth.store';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { NavigationStart, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoDirective } from '@jsverse/transloco';
import { BrowseStore } from './browse.store';
import { attributeLabels, fullDate, templates } from './knowledge.models';

@Component({
  selector: 'app-browse-page',
  imports: [DatePipe, FormsModule, RouterLink, TranslocoDirective],
  templateUrl: './browse-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrowsePage {
  readonly auth = inject(AuthStore);
  readonly store = inject(BrowseStore);
  readonly templates = templates;
  readonly labels = attributeLabels;
  readonly fullDate = fullDate;
  constructor() {
    this.store.enter();
    const position = this.store.scroll();
    afterNextRender(() =>
      window.scrollTo({ top: position, behavior: 'instant' }),
    );
    inject(Router)
      .events.pipe(takeUntilDestroyed())
      .subscribe((event) => {
        if (event instanceof NavigationStart)
          this.store.rememberScroll(window.scrollY);
      });
  }
  selectType(type: string) {
    this.store.refresh(type);
    window.scrollTo({ top: 0, behavior: 'instant' });
  }
  refresh() {
    this.store.refresh();
    window.scrollTo({ top: 0, behavior: 'instant' });
  }
}
