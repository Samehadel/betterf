import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

function fixture(t, version = '0.2.0-rc.1') {
  const root = mkdtempSync(join(tmpdir(), 'betterf-version-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'scripts'));
  mkdirSync(join(root, 'app/frontend'), { recursive: true });
  copyFileSync(new URL('./version.mjs', import.meta.url), join(root, 'scripts/version.mjs'));
  writeFileSync(join(root, 'VERSION'), version + '\n');
  const pkg = { name: 'test', version: '0.1.0', dependencies: { example: '1.2.3' } };
  const lock = { version: '0.1.0', lockfileVersion: 3, packages: { '': pkg, 'node_modules/example': { version: '1.2.3', integrity: 'unchanged' } } };
  writeFileSync(join(root, 'app/frontend/package.json'), JSON.stringify(pkg, null, 2) + '\n');
  writeFileSync(join(root, 'app/frontend/package-lock.json'), JSON.stringify(lock, null, 2) + '\n');
  return {
    run: (...args) => spawnSync(process.execPath, [join(root, 'scripts/version.mjs'), ...args], { cwd: tmpdir(), encoding: 'utf8' }),
    read: name => readFileSync(join(root, 'app/frontend', name), 'utf8'),
    root,
  };
}

test('check detects drift without rewriting metadata', t => {
  const f = fixture(t);
  const before = f.read('package.json');
  assert.equal(f.run('--check').status, 1);
  assert.equal(f.read('package.json'), before);
});

test('sync updates all mirrors, preserves dependency locks, and is idempotent from another cwd', t => {
  const f = fixture(t);
  const before = JSON.parse(f.read('package-lock.json'));
  assert.equal(f.run('--sync').status, 0);
  assert.equal(f.run('--check').status, 0);
  const pkg = JSON.parse(f.read('package.json'));
  const lock = JSON.parse(f.read('package-lock.json'));
  assert.equal(pkg.version, '0.2.0-rc.1');
  assert.equal(lock.version, pkg.version);
  assert.equal(lock.packages[''].version, pkg.version);
  assert.deepEqual(lock.packages['node_modules/example'], before.packages['node_modules/example']);
  assert.deepEqual(pkg.dependencies, before.packages[''].dependencies);
  const synced = f.read('package-lock.json');
  assert.equal(f.run('--sync').status, 0);
  assert.equal(f.read('package-lock.json'), synced);
});

test('invalid versions fail before changing metadata', t => {
  for (const version of ['01.2.3', '1.2', 'v1.2.3', '1.2.3-01', '1.2.3\n2.0.0', '']) {
    const f = fixture(t, version);
    const before = f.read('package.json');
    assert.equal(f.run('--sync').status, 1, version);
    assert.equal(f.read('package.json'), before);
  }
});

test('release and build metadata versions are supported', t => {
  for (const version of ['0.1.0', '1.0.0', '1.2.3-rc.2+sha.abc123']) {
    const f = fixture(t, version);
    assert.equal(f.run('--sync').status, 0);
    assert.equal(f.run('--check').status, 0);
  }
});

test('missing version file and unsupported arguments fail clearly', t => {
  const f = fixture(t);
  assert.equal(f.run('--unexpected').status, 1);
  rmSync(join(f.root, 'VERSION'));
  assert.equal(f.run('--check').status, 1);
});
