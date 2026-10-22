import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, switchMap } from 'rxjs';
export interface Invitation {
  id: string;
  email: string;
  status: string;
  expiresAt: string;
  message: string;
  sentAt?: string | null;
  resendAvailableAt?: string | null;
  resending?: boolean;
  deliveryFailed?: boolean;
}
export interface ActiveMember {
  fullName: string;
  email: string;
  role: string;
}
export interface InvitationPage {
  invitations: Invitation[];
  hasMore: boolean;
}

@Injectable({ providedIn: 'root' })
export class InvitationApi {
  private readonly http = inject(HttpClient);
  sent() {
    return this.http
      .get<{ data: Invitation[] }>('/api/invitations/sent')
      .pipe(map((r) => r.data));
  }
  members() {
    return this.http
      .get<{ data: ActiveMember[] }>('/api/invitations/members')
      .pipe(map((r) => r.data));
  }
  resend(id: string) {
    return this.http
      .get<{ data: { token: string; headerName: string } }>('/api/auth/csrf')
      .pipe(
        switchMap((csrf) =>
          this.http.post<{ data: Invitation }>(
            `/api/invitations/${id}/resend`,
            {},
            { headers: { [csrf.data.headerName]: csrf.data.token } },
          ),
        ),
        map((r) => r.data),
      );
  }
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
