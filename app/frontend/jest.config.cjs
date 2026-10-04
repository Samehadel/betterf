const { createCjsPreset } = require('jest-preset-angular/presets');
module.exports = {
  ...createCjsPreset(),
  // Transloco publishes ESM .js dependencies in addition to Angular's .mjs files.
  transformIgnorePatterns: ['node_modules/(?!(.*\\.mjs$|@angular/common/locales/.*\\.js$|@jsverse/))'],
  setupFilesAfterEnv: ['<rootDir>/setup-jest.ts'],
  testMatch: ['<rootDir>/src/**/*.spec.ts'],
};
