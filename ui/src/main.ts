import {enableProdMode, importProvidersFrom, provideZoneChangeDetection} from '@angular/core';

import {environment} from './environments/environment';
import {AnalyzedSqlService} from './app/analyzed-sql.service';
import {provideHttpClient, withInterceptors, withInterceptorsFromDi} from '@angular/common/http';
import {environmentInterceptor} from './app/environment.interceptor';
import {provideRouter, TitleStrategy, UrlSerializer, withInMemoryScrolling} from '@angular/router';
import {EnvPathUrlSerializer} from './app/env-path-serializer';
import {PageTitleStrategy} from './app/page-title';
import {FormsModule, ReactiveFormsModule} from '@angular/forms';
import {bootstrapApplication} from '@angular/platform-browser';
import {provideAnimations} from '@angular/platform-browser/animations';
import {AppComponent} from './app/app.component';
import {appRoutes} from './app/app.routes';

if (environment.production) {
  enableProdMode();
}

bootstrapApplication(AppComponent, {
    providers: [
        provideZoneChangeDetection({ eventCoalescing: true }),
        provideAnimations(),
        importProvidersFrom(FormsModule, ReactiveFormsModule),
        AnalyzedSqlService,
        { provide: TitleStrategy, useClass: PageTitleStrategy },
        // the environment leads every app path: /prod/queries (sqlx-e13.7)
        { provide: UrlSerializer, useClass: EnvPathUrlSerializer },
        provideHttpClient(withInterceptorsFromDi(), withInterceptors([environmentInterceptor])),
        provideRouter(appRoutes, withInMemoryScrolling({
            // it would be nice to get this working: https://github.com/angular/angular/issues/24547
            scrollPositionRestoration: 'enabled',
            anchorScrolling: 'enabled' }))
    ]
})
  .catch(err => console.log(err));
