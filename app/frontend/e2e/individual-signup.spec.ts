import { test, expect, APIRequestContext } from '@playwright/test';

const mailpit = process.env['MAILPIT_URL'] ?? 'http://127.0.0.1:8025';
async function verification(request: APIRequestContext, email: string) {
  let link = '';
  await expect
    .poll(
      async () => {
        const result = await (
          await request.get(`${mailpit}/api/v1/search?query=${encodeURIComponent('to:' + email)}`)
        ).json();
        if (!result.messages?.length) return false;
        const message = await (
          await request.get(`${mailpit}/api/v1/message/${result.messages[0].ID}`)
        ).json();
        link = /http[^\s]+\/verify#[^\s]+/.exec(message.Text)?.[0] ?? '';
        return !!link;
      },
      { timeout: 20000 },
    )
    .toBe(true);
  const url = new URL(link);
  return url.pathname + url.hash;
}

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
]) {
  test.describe(`Personal signup at ${viewport.width}px`, () => {
    test.use({ viewport });
    test('verifies email and opens an isolated personal knowledge workspace', async ({
      page,
      request,
    }) => {
      const email = `personal-${viewport.width}-${Date.now()}@example.com`;
      await page.goto('/');
      await page.getByRole('link', { name: 'Create personal account', exact: true }).click();
      await expect(page).toHaveURL(/\/signup$/);
      await expect(page.getByLabel('Company name', { exact: true })).toHaveCount(0);
      await expect(page.getByLabel('Professional role', { exact: true })).toHaveCount(0);
      await expect(
        page.getByText('Individual accounts cannot join an organization', { exact: false }),
      ).toBeVisible();
      await page.getByLabel('Full name', { exact: true }).fill('Personal Browser');
      await page.getByLabel('Email address', { exact: true }).fill(email);
      await page.getByLabel('Password', { exact: true }).fill('Validpass!');
      await page.getByRole('button', { name: 'Create personal account', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Verification email status' })).toBeVisible();
      await page.goto(await verification(request, email));
      await expect(page).toHaveURL(/\/knowledge$/);
      await expect(page.getByRole('heading', { name: 'Knowledge', exact: true })).toBeVisible();
      await expect(page.getByText('Private to you', { exact: true }).last()).toBeVisible();
      await expect(page.getByRole('link', { name: 'Team', exact: true })).toHaveCount(0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      const me = await (await page.request.get('/api/auth/me')).json();
      expect(me.data.organizationId).toBeNull();
      expect(me.data.accessRole).toBe('INDIVIDUAL');
      await page.goto('/knowledge/new');
      await expect(
        page.getByText('Only you can view these tickets.', { exact: false }),
      ).toBeVisible();
      await page.getByLabel('Title Required', { exact: true }).fill('My private lesson');
      await page.getByLabel('Writing template', { exact: true }).selectOption('Coding');
      await page.getByLabel('Code 2', { exact: true }).fill('return personal;');
      await page.getByRole('button', { name: 'Publish ticket', exact: true }).click();
      await expect(page).toHaveURL(/\/knowledge\/tickets\//);
      await expect(page.getByRole('heading', { name: 'My private lesson' })).toBeVisible();
      await page.goto('/knowledge');
      await expect(page.getByRole('link', { name: /My private lesson/ })).toBeVisible();
    });
  });
}

test('provider denial has actionable feedback and linking requires explicit confirmation', async ({
  page,
}) => {
  let confirmed = false;
  await page.route('**/api/auth/social/providers', (route) =>
    route.fulfill({
      json: {
        data: [
          { id: 'linkedin', available: true },
          { id: 'facebook', available: true },
        ],
      },
    }),
  );
  await page.goto('/login?social=PROVIDER_AUTHENTICATION_FAILED');
  await expect(page.getByText('Provider sign-in was cancelled', { exact: false })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Continue with LinkedIn' })).toHaveAttribute(
    'href',
    '/api/auth/oauth/authorize/linkedin',
  );
  await page.route('**/api/auth/social/pending', (route) =>
    route.fulfill({ json: { data: { provider: 'linkedin' } } }),
  );
  await page.route('**/api/auth/csrf', (route) =>
    route.fulfill({ json: { data: { token: 'test', headerName: 'X-CSRF-TOKEN' } } }),
  );
  await page.route('**/api/auth/social/link', (route) => {
    confirmed = true;
    expect(route.request().postDataJSON()).toEqual({});
    return route.fulfill({
      json: {
        data: {
          id: 'personal',
          email: 'person@example.com',
          fullName: 'Person',
          organizationId: null,
          organizationName: null,
          professionalRole: 'OTHER',
          accessRole: 'INDIVIDUAL',
        },
      },
    });
  });
  await page.goto('/link-account');
  await expect(page.getByRole('button', { name: 'Confirm provider link' })).toBeVisible();
  expect(confirmed).toBe(false);
  await page.getByRole('button', { name: 'Confirm provider link' }).click();
  await expect(page.getByRole('heading', { name: 'Your accounts are connected' })).toBeVisible();
  expect(confirmed).toBe(true);
});
