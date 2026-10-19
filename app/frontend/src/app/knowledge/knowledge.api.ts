import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, switchMap } from 'rxjs';
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
  create(submissionId: string, content: TicketContent) {
    return this.post<Ticket>('/api/knowledge/tickets', {
      submissionId,
      content,
    });
  }
  refine(content: TicketContent, field: RefinementField) {
    return this.post<Refinement>('/api/knowledge/tickets/refine', {
      content,
      field,
    });
  }
  private post<T>(url: string, body: unknown) {
    return this.http
      .get<{ data: { token: string; headerName: string } }>('/api/auth/csrf')
      .pipe(
        switchMap((csrf) =>
          this.http.post<{ data: T }>(url, body, {
            headers: { [csrf.data.headerName]: csrf.data.token },
          }),
        ),
        map((r) => r.data),
      );
  }
}
