import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { WorkspaceContext } from './workspace-context';
export const workspaceInterceptor: HttpInterceptorFn = (request, next) => {
  const id = inject(WorkspaceContext).id();
  if (id && /^\/api\/(knowledge|invitations)(\/|$)/.test(request.url)) {
    request = request.clone({ setHeaders: { 'X-Workspace-Id': id } });
  }
  return next(request);
};
