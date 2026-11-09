import {
  provideHttpClient,
  withFetch,
  withInterceptors,
  withNoXsrfProtection,
} from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { MatIconRegistry } from '@angular/material/icon';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { csrfInterceptor } from './core/auth/csrf';
import { sessionInterceptor } from './core/auth/session-interceptor';
import { userActivityInterceptor } from './core/auth/user-activity';
import {
  correlationIdInterceptor,
  problemDetailsInterceptor,
  transientRetryInterceptor,
} from './core/http/interceptors';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' }),
    ),
    provideHttpClient(
      withFetch(),
      // Order matters: requests flow left→right, responses right→left. The problem-details
      // interceptor sits outside the retry so it converts only the final outcome; the session
      // interceptor sits outside that, so it reacts to parsed 401/403 problems.
      //
      //
      // CSRF: the token is bound to the server-side session and fetched from /api/auth/csrf (see
      // core/auth/csrf.ts), never read from a cookie, so Angular's built-in cookie-to-header XSRF
      // support is switched off. The CSRF interceptor is last, closest to the backend, so the
      // session interceptor's one retry after a CSRF refusal gets a fresh token. The activity
      // interceptor marks automatic polling as background for the backend's idle timeout.
      withNoXsrfProtection(),
      withInterceptors([
        correlationIdInterceptor,
        sessionInterceptor,
        problemDetailsInterceptor,
        transientRetryInterceptor,
        userActivityInterceptor,
        csrfInterceptor,
      ]),
    ),
    { provide: MAT_FORM_FIELD_DEFAULT_OPTIONS, useValue: { appearance: 'outline' } },
    provideAppInitializer(() => {
      // Icons use the self-hosted Material Symbols subset (styles.scss), not legacy Material Icons.
      inject(MatIconRegistry).setDefaultFontSetClass('material-symbols-outlined');
    }),
  ],
};
