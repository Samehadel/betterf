import { HttpInterceptorFn } from '@angular/common/http';
import { timeout } from 'rxjs';

export const apiTimeoutInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/')) return next(request);

  // Registration may wait for bounded SMTP connection, write, and response timeouts.
  const deadline = request.url.startsWith('/api/registration') ? 15000 : 5000;
  return next(request).pipe(timeout(deadline));
};
