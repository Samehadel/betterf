import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, switchMap } from 'rxjs';
import { Account } from '../identity/identity.api';

export interface InvitationLink {
  id: string;
  token: string;
}
export interface AcceptancePreview {
  status: string;
  message: string;
  companyName: string | null;
  email: string | null;
}
export interface MemberRegistration {
  invitation: InvitationLink;
  fullName: string;
  professionalRole: string;
  password: string;
}
@Injectable({ providedIn: 'root' })
export class AcceptanceApi {
  private readonly http = inject(HttpClient);

  private post<T>(action: string, body: unknown) {
    return this.http.get<{ data: { token: string; headerName: string } }>('/api/auth/csrf').pipe(
      switchMap(({ data }) =>
        this.http.post<{ data: T }>(`/api/auth/invitation/${action}`, body, {
          headers: { [data.headerName]: data.token },
        }),
      ),
      map(({ data }) => data),
    );
  }

  preview(link: InvitationLink) {
    return this.post<AcceptancePreview>('preview', link);
  }

  accept(body: MemberRegistration) {
    return this.post<Account>('accept', body);
  }
}
