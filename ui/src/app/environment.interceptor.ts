import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { EnvironmentService } from './environment.service';

/**
 * Stamps the selected environment onto every SQL Explorer API request so the
 * backend resolves schema and SQL repositories for that environment.
 */
export const environmentInterceptor: HttpInterceptorFn = (req, next) => {
  const current = inject(EnvironmentService).getCurrent();
  if (current && req.url.includes('/api/sql-explorer/')) {
    req = req.clone({ setHeaders: { 'X-Sql-Explorer-Environment': current } });
  }
  return next(req);
};
