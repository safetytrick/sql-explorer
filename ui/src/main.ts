import {enableProdMode, importProvidersFrom, provideZoneChangeDetection} from '@angular/core';

import {environment} from './environments/environment';
import {AnalyzedSqlService} from './app/analyzed-sql.service';
import {HIGHLIGHT_OPTIONS, HighlightModule} from 'ngx-highlightjs';
import {provideHttpClient, withInterceptors, withInterceptorsFromDi} from '@angular/common/http';
import {environmentInterceptor} from './app/environment.interceptor';
import {provideRouter, TitleStrategy, withInMemoryScrolling} from '@angular/router';
import {PageTitleStrategy} from './app/page-title';
import {FormsModule, ReactiveFormsModule} from '@angular/forms';
import {bootstrapApplication} from '@angular/platform-browser';
import {provideAnimations} from '@angular/platform-browser/animations';
import {NgxGraphModule} from '@swimlane/ngx-graph';
import {AppComponent} from './app/app.component';
import {appRoutes} from './app/app.routes';

if (environment.production) {
  enableProdMode();
}

bootstrapApplication(AppComponent, {
    providers: [
        provideZoneChangeDetection({ eventCoalescing: true }),
        provideAnimations(),
        importProvidersFrom(FormsModule, ReactiveFormsModule, HighlightModule, NgxGraphModule),
        AnalyzedSqlService,
        { provide: TitleStrategy, useClass: PageTitleStrategy },
        {
            provide: HIGHLIGHT_OPTIONS,
            useValue: {
                coreLibraryLoader: () => import('highlight.js/lib/core'),
                languages: {
                    sql: () => import('highlight.js/lib/languages/sql'),
                    xml: () => import('highlight.js/lib/languages/xml'),
                }
            }
        },
        provideHttpClient(withInterceptorsFromDi(), withInterceptors([environmentInterceptor])),
        provideRouter(appRoutes, withInMemoryScrolling({
            // it would be nice to get this working: https://github.com/angular/angular/issues/24547
            scrollPositionRestoration: 'enabled',
            anchorScrolling: 'enabled' }))
    ]
})
  .catch(err => console.log(err));
