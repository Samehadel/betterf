import { expect, test } from '@playwright/test';

const account = {
  id: 'ada',
  fullName: 'Ada Shared',
  email: 'ada@example.com',
  professionalRole: 'OTHER',
  workspaceId: 'acme',
  workspaceName: 'Shared Acme',
  accessRole: 'OWNER',
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
      await page.goto('/workspace');
      await expect(
        page.getByRole('heading', { name: account.workspaceName }),
      ).toBeVisible();
      for (let visit = 0; visit < 2; visit++) {
        await page.getByRole('link', { name: 'Invite a colleague' }).click();
        await expect(
          page.getByRole('heading', { name: 'Invite a colleague' }),
        ).toBeVisible();
        const navigation = page.getByRole('button', {
          name: 'Open navigation',
        });
        if (await navigation.isVisible()) {
          await navigation.click();
        }
        await page.getByRole('link', { name: 'Home', exact: true }).click();
        await expect(
          page.getByRole('heading', { name: account.workspaceName }),
        ).toBeVisible();
      }
      expect(meCalls).toBe(1);
      await page.reload();
      await expect(
        page.getByRole('heading', { name: account.workspaceName }),
      ).toBeVisible();
      expect(meCalls).toBe(2);
    });

    test('keeps anonymous startup public, shares login account, and clears it on logout', async ({
      page,
    }) => {
      let meCalls = 0;
      let signedIn = false;
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
        } else if (pathname.startsWith('/api/workspaces/')) {
          await route.fulfill(
            signedIn
              ? { json: { data: account } }
              : {
                  status: 403,
                  json: { error: { code: 'WORKSPACE_ACCESS_DENIED' } },
                },
          );
        } else if (pathname === '/api/auth/login') {
          signedIn = true;
          await route.fulfill({ json: { data: account } });
        } else if (pathname === '/api/auth/logout') {
          signedIn = false;
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
            name: 'Create account',
            exact: true,
          })
          .first(),
      ).toBeVisible();
      await expect(page).toHaveURL(/\/$/);
      const startupCalls = meCalls;
      await page.getByRole('link', { name: 'Log in', exact: true }).click();
      await page
        .getByLabel('Email address', { exact: true })
        .first()
        .fill(account.email);
      await page.getByLabel('Password', { exact: true }).fill('Validpass!');
      await page.getByRole('button', { name: 'Log in', exact: true }).click();
      await expect(
        page.getByRole('heading', { name: 'Knowledge', exact: true }),
      ).toBeVisible();
      expect(meCalls).toBe(startupCalls);
      const navigation = page.getByRole('button', { name: 'Open navigation' });
      if (await navigation.isVisible()) {
        await navigation.click();
      }
      await page.getByLabel('Your account', { exact: true }).click();
      await page.getByRole('button', { name: 'Log out' }).click();
      await expect(page).toHaveURL(/\/login$/);
      await page.goto('/knowledge');
      await expect(page).toHaveURL(/\/login$/);
      await expect(
        page.getByRole('heading', { name: 'Knowledge', exact: true }),
      ).toHaveCount(0);
      expect(meCalls).toBeGreaterThanOrEqual(startupCalls);
    });
  });
}
