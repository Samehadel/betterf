import { Injectable, signal } from '@angular/core';
@Injectable({ providedIn: 'root' })
export class WorkspaceContext {
  readonly id = signal<string | null>(null);
}
