import { test, expect } from '@playwright/test';

test('public landing page remains independent of backend availability', async ({ page }) => {
  let apiRequests = 0;
  await page.route('**/api/**', route => { apiRequests++; return route.abort(); });
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Great teams');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
  await page.getByRole('link', { name: 'Explore the vision', exact: true }).nth(1).click();
  await expect(page).toHaveURL(/#workflow$/);
  await expect(page.getByRole('region', { name: 'Illustrative product preview' })).toBeInViewport();
  expect(apiRequests).toBe(0);
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
