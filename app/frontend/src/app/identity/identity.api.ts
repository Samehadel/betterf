import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { switchMap, map } from 'rxjs';
export interface Registration {
  companyName: string;
  website: string;
  specialization: string;
  fullName: string;
  email: string;
  password: string;
  professionalRole: string;
}
export type IndividualRegistration = Pick<Registration, 'fullName' | 'email' | 'password'>;
export interface Provider {
  id: 'linkedin' | 'facebook';
  available: boolean;
}
export interface Pending {
  email: string;
  resendAvailableAt: string;
  deliveryStatus: 'PENDING' | 'SENDING' | 'SMTP_ACCEPTED' | 'FAILED' | 'CANCELLED';
}
export interface Role {
  id: string;
  label: string;
}
export interface Account {
  id: string;
  fullName: string;
  email: string;
  professionalRole: string;
  organizationId: string | null;
  organizationName: string | null;
  accessRole: string;
}
interface Envelope<T> {
  data: T;
  error: null;
}
@Injectable({ providedIn: 'root' })
export class IdentityApi {
  private readonly http = inject(HttpClient);
  roles() {
    return this.http
      .get<Envelope<Role[]>>('/api/registration/roles')
      .pipe(map((result) => result.data));
  }
  current() {
    return this.http.get<Envelope<Account>>('/api/auth/me').pipe(map((result) => result.data));
  }
  private post<T>(url: string, body: unknown) {
    return this.http.get<Envelope<{ token: string; headerName: string }>>('/api/auth/csrf').pipe(
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
  registerIndividual(body: IndividualRegistration) {
    return this.post<Pending>('/api/registration/individual', body);
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
