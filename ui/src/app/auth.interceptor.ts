import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { BrowserNavigation } from './user.service';

/**
 * A 401 from the API means the session is missing or expired: go and sign
 * in, then come back to this page (sqlx-e13.1).
 *
 * <p>The backend answers 401 rather than redirecting to the login page,
 * because an XHR cannot follow a redirect to another site. So the page itself
 * navigates, to the login entry point, which does. The error still reaches
 * the caller, which matters only for the moment before the page unloads.
 *
 * <p>Only the API's own 401s: a request to anywhere else is not ours to sign
 * in to. With sign-in off the API never answers 401, so this never fires.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const navigation = inject(BrowserNavigation);
  return next(req).pipe(catchError(error => {
    if (error instanceof HttpErrorResponse && error.status === 401 && req.url.includes('/api/sql-explorer/')) {
      navigation.signIn();
    }
    return throwError(() => error);
  }));
};
