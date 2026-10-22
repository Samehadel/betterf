import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map } from 'rxjs';

export interface StatusResponse {
  data: { status: 'UP' } | null;
  error: { code: string; message: string } | null;
}

@Injectable({ providedIn: 'root' })
export class StatusApi {
  private readonly http = inject(HttpClient);

  check() {
    return this.http.get<StatusResponse>('/api/status').pipe(map(response => {
      if (response?.data?.status !== 'UP' || response.error !== null) {
        throw new Error('Invalid backend status response');
      }
      return response.data;
    }));
  }
}
