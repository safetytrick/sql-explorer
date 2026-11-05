import { EnvironmentProviders, Provider } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AnalyzedSqlService } from '../analyzed-sql.service';

/**
 * Providers the component smoke tests need: an empty router (ActivatedRoute /
 * RouterLink) and a stubbed HttpClient (requests pend, nothing hits the network).
 */
export const COMMON_TEST_PROVIDERS: (Provider | EnvironmentProviders)[] = [
  provideRouter([]),
  provideHttpClient(),
  provideHttpClientTesting(),
  // provided by AppComponent in the app; standalone component specs need it here
  AnalyzedSqlService,
];
