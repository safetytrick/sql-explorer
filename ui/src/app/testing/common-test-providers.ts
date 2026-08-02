import { EnvironmentProviders, Provider } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { HIGHLIGHT_OPTIONS } from 'ngx-highlightjs';
import { AnalyzedSqlService } from '../analyzed-sql.service';

/**
 * Providers the component smoke tests need: an empty router (ActivatedRoute /
 * RouterLink), a stubbed HttpClient (requests pend, nothing hits the network),
 * and the highlight.js options mirroring main.ts.
 */
export const COMMON_TEST_PROVIDERS: (Provider | EnvironmentProviders)[] = [
  provideRouter([]),
  provideHttpClient(),
  provideHttpClientTesting(),
  // provided by AppComponent in the app; standalone component specs need it here
  AnalyzedSqlService,
  {
    provide: HIGHLIGHT_OPTIONS,
    useValue: {
      coreLibraryLoader: () => import('highlight.js/lib/core'),
      languages: {
        sql: () => import('highlight.js/lib/languages/sql'),
        xml: () => import('highlight.js/lib/languages/xml'),
      },
    },
  },
];
