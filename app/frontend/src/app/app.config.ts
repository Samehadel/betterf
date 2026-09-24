import { ApplicationConfig, isDevMode, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { routes } from './app.routes';
import { TranslationLoader } from './core/translation-loader';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes),
    provideTransloco({
      config: { availableLangs: ['en'], defaultLang: 'en', fallbackLang: 'en', prodMode: !isDevMode() },
      loader: TranslationLoader,
    }),
  ],
};
