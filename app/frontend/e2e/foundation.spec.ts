import { test, expect } from '@playwright/test';

test('the shell connects to the real backend', async ({ page }) => {
  await page.goto('/status');
  await expect(page.getByRole('heading', { name: 'BetterF', exact: true })).toBeVisible();
  await expect(page.getByRole('status')).toHaveText('Connected');
  await page.getByRole('button', { name: 'Check again' }).click();
  await expect(page.getByRole('status')).toHaveText('Connected');
});

test('an unavailable backend is recoverable using the keyboard', async ({ page }) => {
  await page.route('**/api/status', route => route.abort('connectionrefused'));
  await page.goto('/status');
  await expect(page.getByRole('status')).toContainText('Backend unavailable');
  await page.unroute('**/api/status');
  const retry = page.getByRole('button', { name: 'Try again' });
  await retry.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('status')).toHaveText('Connected');
});

test('the shell fits a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/status');
  await expect(page.getByRole('status')).toHaveText('Connected');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
