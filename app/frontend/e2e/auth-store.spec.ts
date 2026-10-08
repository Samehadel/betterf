import { expect, test } from '@playwright/test';

const account = {
  id: 'ada',
  fullName: 'Ada Shared',
  email: 'ada@example.com',
  professionalRole: 'OTHER',
  organizationId: 'acme',
  organizationName: 'Shared Acme',
  accessRole: 'ADMIN',
};

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
]) {
  test.describe(`Authentication at ${viewport.width}px`, () => {
    test.use({ viewport });
    test('loads once per app startup and reuses account through company navigation', async ({
      page,
    }) => {
      let meCalls = 0;
      await page.route('**/api/**', async (route) => {
        const pathname = new URL(route.request().url()).pathname;
        if (pathname === '/api/auth/me') {
          meCalls++;
          await route.fulfill({ json: { data: account, error: null } });
        } else if (pathname === '/api/invitations') {
          await route.fulfill({
            json: { data: { invitations: [], hasMore: false }, error: null },
          });
        } else {
          await route.fulfill({ json: { data: {}, error: null } });
        }
      });
      await page.goto('/company');
      await expect(
        page.getByRole('heading', { name: account.organizationName }),
      ).toBeVisible();
      for (let visit = 0; visit < 2; visit++) {
        await page.getByRole('link', { name: 'Invite a colleague' }).click();
        await expect(
          page.getByRole('heading', { name: 'Invite a colleague' }),
        ).toBeVisible();
        await page.getByRole('link', { name: 'Back to company' }).click();
        await expect(
          page.getByRole('heading', { name: account.organizationName }),
        ).toBeVisible();
      }
      expect(meCalls).toBe(1);
      await page.reload();
      await expect(
        page.getByRole('heading', { name: account.organizationName }),
      ).toBeVisible();
      expect(meCalls).toBe(2);
    });

    test('keeps anonymous startup public, shares login account, and clears it on logout', async ({
      page,
    }) => {
      let meCalls = 0;
      await page.route('**/api/**', async (route) => {
        const pathname = new URL(route.request().url()).pathname;
        if (pathname === '/api/auth/me' || pathname === '/api/auth/refresh') {
          if (pathname === '/api/auth/me') meCalls++;
          await route.fulfill({
            status: 401,
            json: { data: null, error: { code: 'AUTHENTICATION_REQUIRED' } },
          });
        } else if (pathname === '/api/auth/csrf') {
          await route.fulfill({
            json: { data: { token: 'csrf', headerName: 'X-CSRF-TOKEN' } },
          });
        } else if (pathname === '/api/auth/login') {
          await route.fulfill({ json: { data: account } });
        } else if (pathname === '/api/auth/logout') {
          await route.fulfill({ status: 204 });
        } else {
          await route.fulfill({
            json: { data: { invitations: [], hasMore: false } },
          });
        }
      });
      await page.goto('/');
      await expect(
        page
          .getByRole('link', {
            name: 'Register your organization',
            exact: true,
          })
          .first(),
      ).toBeVisible();
      await expect(page).toHaveURL(/\/$/);
      const startupCalls = meCalls;
      await page.getByRole('link', { name: 'Log in', exact: true }).click();
      await page
        .getByLabel('Organization email', { exact: true })
        .first()
        .fill(account.email);
      await page.getByLabel('Password', { exact: true }).fill('Validpass!');
      await page.getByRole('button', { name: 'Log in', exact: true }).click();
      await expect(
        page.getByRole('heading', { name: account.organizationName }),
      ).toBeVisible();
      expect(meCalls).toBe(startupCalls);
      await page.getByRole('button', { name: 'Log out' }).click();
      await expect(page).toHaveURL(/\/login$/);
      await page.goBack();
      await expect(
        page.getByText(
          'Log in with a verified account to access your organization.',
        ),
      ).toBeVisible();
      await expect(
        page.getByRole('heading', { name: account.organizationName }),
      ).toHaveCount(0);
      expect(meCalls).toBe(startupCalls);
    });
  });
}
