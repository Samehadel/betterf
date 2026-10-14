import { expect, test } from '@playwright/test';

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
]) {
  test.describe(`Team management at ${viewport.width}px`, () => {
    test.use({ viewport });
    test('shows complete lists, accessible tabs, cooldown and failed resend feedback', async ({
      page,
    }) => {
      const sentAt = new Date(Date.now() - 120000).toISOString();
      const expiresAt = new Date(Date.now() + 86400000).toISOString();
      const rows = Array.from({ length: 30 }, (_, index) => ({
        id: String(index),
        email: `person${index}@example.com`,
        status:
          index === 0 ? 'ACCEPTED' : index === 1 ? 'FAILED' : 'SMTP_ACCEPTED',
        sentAt: index === 1 ? null : sentAt,
        expiresAt,
        resendAvailableAt:
          index === 2 ? new Date(Date.now() + 60000).toISOString() : sentAt,
      }));
      let failMembers = true;
      await page.route('**/api/**', async (route) => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/auth/me')
          await route.fulfill({
            json: {
              data: {
                fullName: 'Ada',
                accessRole: 'ADMIN',
                organizationName: 'Acme',
              },
            },
          });
        else if (path === '/api/invitations/sent')
          await route.fulfill({ json: { data: rows } });
        else if (path === '/api/invitations/members') {
          if (failMembers)
            await route.fulfill({
              status: 503,
              json: { error: { message: 'Please retry loading members.' } },
            });
          else
            await route.fulfill({
              json: {
                data: [
                  {
                    fullName: 'Member Name',
                    email: 'member@example.com',
                    professionalRole: 'Software Engineer',
                  },
                ],
              },
            });
        } else if (path === '/api/auth/csrf')
          await route.fulfill({
            json: { data: { token: 'csrf', headerName: 'X-CSRF-TOKEN' } },
          });
        else if (path.endsWith('/resend'))
          await route.fulfill({
            json: {
              data: {
                ...rows[3],
                deliveryFailed: true,
                message: 'Delivery failed. The previous link remains valid.',
              },
            },
          });
      });
      await page.goto('/company/team');
      await expect(
        page.getByRole('tab', { name: 'Sent invitations' }),
      ).toHaveAttribute('aria-selected', 'true');
      await expect(page.getByRole('row')).toHaveCount(31);
      await expect(
        page.getByRole('row').filter({ hasText: 'person0@example.com' }),
      ).toContainText('Accepted');
      await expect(
        page
          .getByRole('row')
          .filter({ hasText: 'person0@example.com' })
          .getByRole('button'),
      ).toHaveCount(0);
      await expect(
        page.getByRole('row').filter({ hasText: 'person1@example.com' }),
      ).toContainText('Not sent');
      await expect(
        page.getByRole('button', {
          name: 'Resend person2@example.com',
          exact: true,
        }),
      ).toBeDisabled();
      await page
        .getByRole('button', {
          name: 'Resend person3@example.com',
          exact: true,
        })
        .click();
      await expect(page.getByRole('status')).toContainText(
        'Delivery failed. The previous link remains valid.',
      );
      await page.getByRole('tab', { name: 'Sent invitations' }).focus();
      await page.keyboard.press('ArrowRight');
      await expect(
        page.getByRole('tab', { name: 'Active members' }),
      ).toBeFocused();
      await expect(page.getByRole('alert')).toContainText('Please retry');
      failMembers = false;
      await page.getByRole('button', { name: 'Try again' }).click();
      await expect(
        page.getByRole('cell', { name: 'Member Name' }),
      ).toBeVisible();
      await expect(page.getByRole('columnheader')).toHaveCount(3);
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
    });

    test('shows empty and permission states', async ({ page }) => {
      let admin = true;
      await page.route('**/api/**', async (route) => {
        const path = new URL(route.request().url()).pathname;
        await route.fulfill({
          json: {
            data:
              path === '/api/auth/me'
                ? { accessRole: admin ? 'ADMIN' : 'MEMBER' }
                : [],
          },
        });
      });
      await page.goto('/company/team');
      await expect(
        page.getByRole('heading', { name: 'No sent invitations' }),
      ).toBeVisible();
      await page.getByRole('tab', { name: 'Active members' }).click();
      await expect(
        page.getByRole('heading', { name: 'No active members yet' }),
      ).toBeVisible();
      admin = false;
      await page.reload();
      await expect(page.getByRole('alert')).toBeVisible();
      await expect(page.getByRole('tab')).toHaveCount(0);
    });
  });
}
