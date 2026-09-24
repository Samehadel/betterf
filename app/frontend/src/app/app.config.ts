import { ApplicationConfig, isDevMode, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { routes } from './app.routes';
import { apiTimeoutInterceptor } from './core/api-timeout.interceptor';
import { TranslationLoader } from './core/translation-loader';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([apiTimeoutInterceptor])),
    provideTransloco({
      config: { availableLangs: ['en'], defaultLang: 'en', fallbackLang: 'en', prodMode: !isDevMode() },
      loader: TranslationLoader,
    }),
  ],
};
