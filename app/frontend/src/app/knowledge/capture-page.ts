import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  HostListener,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { TranslocoService } from '@jsverse/transloco';
import { KnowledgeStore } from './knowledge.store';
import {
  DetailBlock,
  RefinementField,
  TicketContent,
  blockTypes,
  detailsLength,
  emptyBlock,
  languages,
  templates,
  validContent,
} from './knowledge.models';
import { DetailsView } from './details-view';
@Component({
  providers: [KnowledgeStore],
  selector: 'app-capture-page',
  imports: [FormsModule, RouterLink, TranslocoDirective, DetailsView],
  templateUrl: './capture-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CapturePage {
  readonly store = inject(KnowledgeStore);
  readonly content = signal<TicketContent>({
    title: '',
    capture: '',
    template: 'Knowledge',
    details: [emptyBlock('paragraph')],
  });
  readonly dirty = signal(false);
  readonly preview = signal(false);
  readonly templates = templates;
  readonly languages = languages;
  readonly blockTypes = blockTypes;
  readonly length = computed(() => detailsLength(this.content().details));
  readonly valid = computed(() => validContent(this.content()));
  readonly locked = computed(
    () => this.store.busy() || this.store.refining() || this.store.uncertain(),
  );
  private readonly router = inject(Router);
  private readonly translation = inject(TranslocoService);
  private readonly submissionId = crypto.randomUUID();
  constructor() {
    effect(() => {
      const ticket = this.store.ticket();
      if (!ticket) return;
      this.dirty.set(false);
      void this.router.navigate(['/knowledge/tickets', ticket.id], {
        state: { created: true },
      });
    });
  }
  update(field: 'title' | 'capture' | 'template', value: string) {
    this.changed();
    this.content.update((c) => ({ ...c, [field]: value }));
    if (
      field === 'template' &&
      value === 'Coding' &&
      !this.content().details.some((b) => b.type === 'code')
    )
      this.add('code');
  }
  block(index: number, patch: Partial<DetailBlock>) {
    this.changed();
    this.content.update((c) => ({
      ...c,
      details: c.details.map((b, i) => (i === index ? { ...b, ...patch } : b)),
    }));
  }
  add(type: DetailBlock['type']) {
    this.changed();
    this.content.update((c) => ({
      ...c,
      details: [...c.details, emptyBlock(type)],
    }));
  }
  remove(index: number) {
    this.changed();
    this.content.update((c) => ({
      ...c,
      details: c.details.filter((_, i) => i !== index),
    }));
  }
  move(index: number, offset: number) {
    this.changed();
    this.content.update((c) => {
      const details = [...c.details];
      [details[index], details[index + offset]] = [
        details[index + offset],
        details[index],
      ];
      return { ...c, details };
    });
  }
  changed() {
    this.dirty.set(true);
    this.store.discard();
  }
  refine(field: RefinementField) {
    if (this.valid() && !this.locked())
      this.store.refine({ content: this.content(), field });
  }
  accept() {
    const suggestion = this.store.suggestion();
    if (!suggestion) return;
    this.content.update((c) => ({ ...c, [suggestion.field]: suggestion.text }));
    this.changed();
  }
  publish() {
    if (this.valid() && !this.store.busy() && !this.store.refining())
      this.store.publish({ id: this.submissionId, content: this.content() });
  }
  canLeave() {
    return (
      !this.dirty() ||
      window.confirm(this.translation.translate('knowledge.leave'))
    );
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent) {
    if (this.dirty()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
}
