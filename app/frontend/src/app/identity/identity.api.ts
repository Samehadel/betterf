import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { switchMap, map } from 'rxjs';
export interface Registration {
  fullName: string;
  email: string;
  password: string;
}
export interface Provider {
  id: 'linkedin' | 'facebook';
  available: boolean;
}
export interface Pending {
  email: string;
  resendAvailableAt: string;
  deliveryStatus:
    'PENDING' | 'SENDING' | 'SMTP_ACCEPTED' | 'FAILED' | 'CANCELLED';
}
export interface Account {
  id: string;
  fullName: string;
  email: string;
  workspaceId: string | null;
  workspaceName: string | null;
  accessRole: string;
  workspaces: { id: string; name: string; role: string }[];
}
interface Envelope<T> {
  data: T;
  error: null;
}
@Injectable({ providedIn: 'root' })
export class IdentityApi {
  private readonly http = inject(HttpClient);
  selectWorkspace(id: string) {
    return this.http
      .get<Envelope<Account>>('/api/workspaces/' + id)
      .pipe(map((result) => result.data));
  }
  renameWorkspace(id: string, name: string) {
    return this.http
      .get<Envelope<{ token: string; headerName: string }>>('/api/auth/csrf')
      .pipe(
        switchMap(({ data }) =>
          this.http.patch<Envelope<Account>>(
            '/api/workspaces/' + id,
            { name },
            { headers: { [data.headerName]: data.token } },
          ),
        ),
        map((result) => result.data),
      );
  }
  createWorkspace(name: string) {
    return this.post<Account>('/api/workspaces', { name });
  }
  current() {
    return this.http
      .get<Envelope<Account>>('/api/auth/me')
      .pipe(map((result) => result.data));
  }
  private post<T>(url: string, body: unknown) {
    return this.http
      .get<Envelope<{ token: string; headerName: string }>>('/api/auth/csrf')
      .pipe(
        switchMap((csrf) =>
          this.http.post<Envelope<T>>(url, body, {
            headers: { [csrf.data.headerName]: csrf.data.token },
          }),
        ),
        map((result) => result?.data),
      );
  }
  providers() {
    return this.http
      .get<Envelope<Provider[]>>('/api/auth/social/providers')
      .pipe(map((result) => result.data));
  }
  pendingLink() {
    return this.http
      .get<Envelope<{ provider: string | null }>>('/api/auth/social/pending')
      .pipe(map((result) => result.data));
  }
  linkProvider() {
    return this.post<Account>('/api/auth/social/link', {});
  }
  register(body: Registration) {
    return this.post<Pending>('/api/registration', body);
  }
  deliveryStatus(email: string) {
    return this.post<Pending>('/api/registration/status', { email });
  }
  resend(email: string) {
    return this.post<Pending>('/api/registration/resend', { email });
  }
  verify(id: string, token: string) {
    return this.post<{
      status: 'VERIFIED' | 'ALREADY_VERIFIED';
      account: Account | null;
    }>('/api/registration/verify', { id, token });
  }
  login(email: string, password: string) {
    return this.post<Account>('/api/auth/login', { email, password });
  }
  logout() {
    return this.post<void>('/api/auth/logout', {});
  }
}
