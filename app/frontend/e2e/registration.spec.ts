import { test, expect, APIRequestContext, Page } from '@playwright/test';
const mailpit = process.env['MAILPIT_URL'] ?? 'http://127.0.0.1:8025';
const password = 'a long browser passphrase';
async function latestLink(
  request: APIRequestContext,
  email: string,
  previous?: string,
): Promise<string> {
  let link = '';
  await expect
    .poll(
      async () => {
        const response = await request.get(
          `${mailpit}/api/v1/search?query=${encodeURIComponent('to:' + email)}`,
        );
        const list = await response.json();
        if (!list.messages?.length) return false;
        const message = await (
          await request.get(`${mailpit}/api/v1/message/${list.messages[0].ID}`)
        ).json();
        link = /http[^\s]+\/verify#[^\s]+/.exec(message.Text)?.[0] ?? '';
        return !!link && link !== previous;
      },
      { timeout: 15000 },
    )
    .toBe(true);
  return link;
}
async function fillRegistration(page: Page, email: string, website: string) {
  await page.getByLabel('Company name', { exact: true }).fill('Browser Acme');
  await page.getByLabel('Company website', { exact: true }).fill(website);
  await page.getByLabel('Specialization', { exact: true }).fill('Software engineering');
  await page.getByLabel('Full name', { exact: true }).fill('Ada Browser');
  await page.getByLabel('Organization email', { exact: true }).first().fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByLabel('Professional role', { exact: true }).selectOption('OTHER');
}
test('landing registration, local email verification, password login and logout', async ({
  page,
  request,
}) => {
  const unique = Date.now();
  const email = `browser-${unique}@example.com`;
  await page.goto('/');
  await page.getByRole('link', { name: 'Register your organization', exact: true }).first().click();
  await expect(page).toHaveURL(/\/register$/);
  await expect(page.getByLabel('Professional role', { exact: true })).toHaveCount(1);
  await page.getByRole('button', { name: 'Register your organization', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('correct the required fields');
  await expect(page.getByText('Enter your company name.')).toHaveCSS('color', 'rgb(180, 35, 54)');
  await expect(page.getByText('Enter your company website.')).toBeVisible();
  await expect(page.getByLabel('Company website', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  );
  await expect(page.getByLabel('Password', { exact: true })).toHaveAttribute(
    'aria-describedby',
    'passwordHint passwordError',
  );
  await fillRegistration(page, email, `https://browser-${unique}.com/about`);
  await page.getByRole('button', { name: 'Register your organization', exact: true }).click();
  await expect(page.getByRole('alert').first()).toContainText('HTTPS root website');
  await page.getByLabel('Company website', { exact: true }).fill(`https://browser-${unique}.com`);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Register your organization', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible({
    timeout: 15000,
  });
  await expect(
    page.getByRole('button', { name: 'Send another verification email' }),
  ).toBeDisabled();
  await expect(page.getByLabel('Organization email', { exact: true })).toBeDisabled();
  await expect(page.getByLabel('Organization email', { exact: true })).toHaveValue(email);
  const link = await latestLink(request, email);
  await page.goto('/login');
  await page.getByLabel('Organization email', { exact: true }).last().fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('verify your email');
  await page.goto(link);
  await expect(page).toHaveURL(/\/verify$/);
  await page.getByRole('button', { name: 'Verify email and register organization' }).click();
  await expect(page.getByRole('heading', { name: 'Your organization is ready' })).toBeVisible();
  await page.getByRole('link', { name: 'Log in', exact: true }).last().click();
  await page.getByLabel('Organization email', { exact: true }).last().fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
  await expect(page).toHaveURL(/\/company$/);
  await expect(page.getByText('Welcome, Ada Browser.')).toBeVisible();
  await page.reload();
  await expect(page.getByText('Welcome, Ada Browser.')).toBeVisible();
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/company');
  await expect(
    page.getByText('Log in with a verified account to access your organization.'),
  ).toBeVisible();
});
test('resend rotates link and invalid link exposes recovery; narrow keyboard-accessible form', async ({
  page,
  request,
}) => {
  test.setTimeout(90000);
  const unique = Date.now();
  const email = `resend-${unique}@example.com`;
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/register');
  await fillRegistration(page, email, `https://resend-${unique}.co.uk`);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Register your organization', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible({
    timeout: 15000,
  });
  const old = await latestLink(request, email);
  const resend = page.getByRole('button', { name: 'Send another verification email' });
  await expect(resend).toBeEnabled({ timeout: 65000 });
  const [response] = await Promise.all([
    page.waitForResponse((response) => response.url().endsWith('/api/registration/resend')),
    resend.click(),
  ]);
  expect(response.status()).toBe(200);
  expect(response.request().postDataJSON()).toEqual({ email });
  await expect(page.getByLabel('Organization email', { exact: true })).toBeDisabled();
  await expect(resend).toBeDisabled();
  const replacement = await latestLink(request, email, old);
  expect(replacement).not.toBe(old);
  await page.goto(old);
  await page.getByRole('button', { name: 'Verify email and register organization' }).click();
  await expect(page.getByRole('alert')).toContainText('replaced');
  await expect(page.getByRole('button', { name: 'Send another verification email' })).toBeVisible();
  await page.goto(replacement);
  await page.getByRole('button', { name: 'Verify email and register organization' }).click();
  await expect(page.getByRole('heading', { name: 'Your organization is ready' })).toBeVisible();
});
