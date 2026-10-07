import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, switchMap } from 'rxjs';
export interface Invitation {
  id: string;
  email: string;
  status: string;
  expiresAt: string;
  message: string;
}
export interface InvitationPage {
  invitations: Invitation[];
  hasMore: boolean;
}

@Injectable({ providedIn: 'root' })
export class InvitationApi {
  private readonly http = inject(HttpClient);
  history(page: number) {
    return this.http
      .get<{ data: InvitationPage }>('/api/invitations', { params: { page } })
      .pipe(map((result) => result.data));
  }

  request(email: string, status = false) {
    return this.http
      .get<{ data: { token: string; headerName: string } }>('/api/auth/csrf')
      .pipe(
        switchMap((csrf) =>
          this.http.post<{ data: Invitation }>(
            status ? '/api/invitations/status' : '/api/invitations',
            { email },
            { headers: { [csrf.data.headerName]: csrf.data.token } },
          ),
        ),
        map((result) => result.data),
      );
  }
}
