import { HttpInterceptorFn } from '@angular/common/http';
import { timeout } from 'rxjs';

export const apiTimeoutInterceptor: HttpInterceptorFn = (request, next) =>
  request.url.startsWith('/api/') ? next(request).pipe(timeout(5000)) : next(request);
