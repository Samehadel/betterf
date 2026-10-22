import { inject, Injectable } from '@angular/core';
import { AuthStore } from '../core/auth.store';
import { WorkspaceContext } from '../core/workspace-context';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { map, switchMap, throwError } from 'rxjs';
import {
  Ticket,
  TicketPageResult,
  TicketContent,
  Refinement,
  RefinementField,
} from './knowledge.models';
@Injectable({ providedIn: 'root' })
export class KnowledgeApi {
  private readonly http = inject(HttpClient);
  private readonly context = inject(WorkspaceContext);
  private readonly auth = inject(AuthStore);
  browse(detailsType: string, cursor: string | null) {
    const params: Record<string, string> = {};
    if (detailsType) params['detailsType'] = detailsType;
    if (cursor) params['cursor'] = cursor;
    return this.http
      .get<{ data: TicketPageResult }>('/api/knowledge/tickets', { params })
      .pipe(map((response) => response.data));
  }
  get(id: string) {
    return this.http
      .get<{ data: Ticket }>(`/api/knowledge/tickets/${id}`)
      .pipe(map((r) => r.data));
  }
  create(submissionId: string, content: TicketContent, workspaceId: string) {
    return this.post<Ticket>(
      '/api/knowledge/tickets',
      { submissionId, content },
      workspaceId,
    );
  }
  refine(content: TicketContent, field: RefinementField, workspaceId: string) {
    return this.post<Refinement>(
      '/api/knowledge/tickets/refine',
      { content, field },
      workspaceId,
    );
  }
  private post<T>(url: string, body: unknown, workspaceId: string) {
    const accountId = this.auth.account()?.id;
    return this.http
      .get<{ data: { token: string; headerName: string } }>('/api/auth/csrf')
      .pipe(
        switchMap((csrf) => {
          if (
            !workspaceId ||
            this.context.id() !== workspaceId ||
            this.auth.account()?.id !== accountId
          ) {
            return throwError(
              () =>
                new HttpErrorResponse({
                  status: 409,
                  error: {
                    error: {
                      code: 'WORKSPACE_SELECTION_REQUIRED',
                      message:
                        'This draft belongs to a different workspace. Return to its workspace or start a new capture.',
                    },
                  },
                }),
            );
          }
          return this.http.post<{ data: T }>(url, body, {
            headers: {
              [csrf.data.headerName]: csrf.data.token,
              'X-Workspace-Id': workspaceId,
            },
          });
        }),
        map((r) => r.data),
      );
  }
}
