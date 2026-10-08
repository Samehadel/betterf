import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { AuthStore } from './core/auth.store';
import { routes } from './app.routes';
import { sessionRefreshInterceptor } from './core/session-refresh.interceptor';
import { apiTimeoutInterceptor } from './core/api-timeout.interceptor';
import { TranslationLoader } from './core/translation-loader';

export const appConfig: ApplicationConfig = {
  providers: [
    provideAppInitializer(() => inject(AuthStore).load()),
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes),
    provideHttpClient(
      withInterceptors([sessionRefreshInterceptor, apiTimeoutInterceptor]),
    ),
    provideTransloco({
      config: {
        availableLangs: ['en'],
        defaultLang: 'en',
        fallbackLang: 'en',
        prodMode: !isDevMode(),
      },
      loader: TranslationLoader,
    }),
  ],
};
