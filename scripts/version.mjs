#!/usr/bin/env node
// VERSION is authoritative; npm metadata is a checked-in mirror.
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const mode = process.argv[2];
if (!['--check', '--sync'].includes(mode) || process.argv.length !== 3) {
  console.error('Usage: node scripts/version.mjs --check|--sync');
  process.exit(1);
}

try {
  const version = readFileSync(resolve(root, 'VERSION'), 'utf8').trim();
  const number = '(?:0|[1-9][0-9]*)';
  const prerelease = `(?:${number}|[0-9]*[A-Za-z-][0-9A-Za-z-]*)`;
  const semver = new RegExp(`^${number}\\.${number}\\.${number}(?:-${prerelease}(?:\\.${prerelease})*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$`);
  if (!semver.test(version)) throw new Error('VERSION must contain a valid semantic version, e.g. 0.1.0 or 0.2.0-rc.1');

  const packagePath = resolve(root, 'app/frontend/package.json');
  const lockPath = resolve(root, 'app/frontend/package-lock.json');
  const pkg = JSON.parse(readFileSync(packagePath, 'utf8'));
  const lock = JSON.parse(readFileSync(lockPath, 'utf8'));
  if (!lock.packages?.['']) throw new Error('Expected an npm lockfile with root package metadata');

  const mismatch = pkg.version !== version || lock.version !== version || lock.packages[''].version !== version;
  if (mode === '--check' && mismatch) {
    throw new Error('Frontend version metadata differs from VERSION. Run node scripts/version.mjs --sync and commit the resulting files.');
  }
  if (mode === '--sync' && mismatch) {
    pkg.version = version;
    lock.version = version;
    lock.packages[''].version = version;
    writeFileSync(packagePath, JSON.stringify(pkg, null, 2) + '\n');
    writeFileSync(lockPath, JSON.stringify(lock, null, 2) + '\n');
  }
  console.log(`Application version ${version}: ${mode === '--check' ? 'consistent' : 'synchronized'}`);
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
