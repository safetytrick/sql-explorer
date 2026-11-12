import {Injectable, inject} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {AccessTokenList, AccessTokenView, CreatedToken, CreateTokenRequest, TOKENS_PATH} from './access-tokens';

/**
 * Personal access tokens, `/tokens` (sqlx-e13.5). Global, not per
 * environment: the interceptor leaves the path alone. The backend takes these
 * only from a browser session, so a token can never make another.
 */
@Injectable({providedIn: 'root'})
export class AccessTokenService {

  private readonly http = inject(HttpClient);

  list(all = false): Observable<AccessTokenList> {
    return this.http.get<AccessTokenList>(TOKENS_PATH, all ? {params: {all: 'true'}} : {});
  }

  create(request: CreateTokenRequest): Observable<CreatedToken> {
    return this.http.post<CreatedToken>(TOKENS_PATH, request);
  }

  revoke(id: number): Observable<AccessTokenView> {
    return this.http.delete<AccessTokenView>(`${TOKENS_PATH}/${id}`);
  }
}
