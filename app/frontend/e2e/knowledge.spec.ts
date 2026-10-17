import { expect, test } from '@playwright/test';

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
]) {
  test.describe(`Knowledge capture at ${viewport.width}px`, () => {
    test.use({ viewport });
    test('validates Coding, previews refinement, preserves errors and publishes to the ticket', async ({
      page,
    }) => {
      let refineFails = true;
      let publishFails = true;
      let published: any;
      const ids: string[] = [];
      await page.route('**/api/**', async (route) => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/auth/me')
          return route.fulfill({
            json: {
              data: {
                id: 'author',
                fullName: 'Ada',
                accessRole: 'MEMBER',
                organizationName: 'Acme',
              },
            },
          });
        if (path === '/api/auth/csrf')
          return route.fulfill({
            json: { data: { token: 'csrf', headerName: 'X-CSRF-TOKEN' } },
          });
        if (path.endsWith('/refine')) {
          expect(
            route.request().postDataJSON().content.details[1].text,
          ).toContain('return key;');
          if (refineFails)
            return route.fulfill({
              status: 503,
              json: {
                error: {
                  message: 'Refinement unavailable. Your writing is safe.',
                },
              },
            });
          const field = route.request().postDataJSON().field;
          expect(['title', 'capture']).toContain(field);
          return route.fulfill({
            json: {
              data: {
                field,
                text: field === 'title' ? 'Retry safely' : 'One stable key.',
              },
            },
          });
        }
        if (path === '/api/knowledge/tickets') {
          const request = route.request().postDataJSON();
          ids.push(request.submissionId);
          published = {
            id: ids.length > 2 ? 'ticket-2' : 'ticket-1',
            content: request.content,
            authorId: 'author',
            authorName: 'Ada',
            createdAt: '2026-10-18T00:00:00Z',
            classificationStatus: 'COMPLETE',
            attributes: ['area:backend', 'type:coding'],
          };
          if (publishFails)
            return route.fulfill({
              status: 503,
              json: { error: { message: 'Response lost. Retry safely.' } },
            });
          return route.fulfill({ status: 201, json: { data: published } });
        }
        if (path === '/api/knowledge/tickets/' + published?.id)
          return route.fulfill({ json: { data: published } });
        return route.fulfill({ status: 404 });
      });
      await page.goto('/knowledge/new');
      await expect(
        page.getByRole('button', { name: 'Publish ticket', exact: true }),
      ).toBeDisabled();
      await expect(
        page.getByRole('complementary').getByRole('button', {
          name: 'Refine Title',
          exact: true,
        }),
      ).toBeDisabled();
      await page
        .getByLabel('Title Required', { exact: true })
        .fill('retry safely');
      await page
        .getByLabel('Capture Optional', { exact: true })
        .fill('one stable key');
      await page
        .getByLabel('Text 1', { exact: true })
        .fill('Reuse the payment key on every retry.');
      await page.getByLabel('Writing template').selectOption('Coding');
      await expect(
        page.getByRole('button', { name: 'Publish ticket', exact: true }),
      ).toBeDisabled();
      await page.getByLabel('Code 2', { exact: true }).fill('return key;');
      await page
        .getByRole('button', { name: 'Preview Details', exact: true })
        .click();
      await expect(page.locator('app-details-view pre')).toContainText(
        'return key;',
      );
      await page
        .getByRole('complementary')
        .getByRole('button', { name: 'Refine Title', exact: true })
        .click();
      await expect(page.getByRole('alert')).toContainText(
        'Your writing is safe',
      );
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('retry safely');
      refineFails = false;
      await page
        .getByRole('complementary')
        .getByRole('button', { name: 'Refine Title', exact: true })
        .click();
      await expect(
        page.getByRole('heading', { name: 'Suggested wording' }),
      ).toBeVisible();
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('retry safely');
      await page.getByRole('button', { name: 'Discard', exact: true }).click();
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('retry safely');
      await page
        .getByRole('complementary')
        .getByRole('button', { name: 'Refine Title', exact: true })
        .click();
      await page.getByRole('button', { name: 'Accept wording' }).click();
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('Retry safely');
      await expect(page.getByLabel('Code 2', { exact: true })).toHaveValue(
        'return key;',
      );
      await expect(
        page.getByLabel('Capture Optional', { exact: true }),
      ).toHaveValue('one stable key');
      await expect(page.locator('.knowledge-editor .knowledge-ai')).toHaveCount(
        0,
      );
      await expect(
        page.getByRole('button', { name: /Details.*wording|Use Details/ }),
      ).toHaveCount(0);
      await page
        .getByRole('complementary')
        .getByRole('button', { name: 'Refine Capture', exact: true })
        .click();
      await page.getByRole('button', { name: 'Accept wording' }).click();
      await expect(
        page.getByLabel('Capture Optional', { exact: true }),
      ).toHaveValue('One stable key.');
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('Retry safely');
      await expect(page.getByLabel('Code 2', { exact: true })).toHaveValue(
        'return key;',
      );

      page.once('dialog', (dialog) => dialog.dismiss());
      await page.getByRole('link', { name: 'Cancel', exact: true }).click();
      await expect(page).toHaveURL(/knowledge\/new/);
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
      await page
        .getByRole('button', { name: 'Publish ticket', exact: true })
        .click();
      await expect(page.getByRole('alert')).toContainText('Response lost');
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('Retry safely');
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toBeDisabled();
      publishFails = false;
      await page
        .getByRole('button', { name: 'Publish ticket', exact: true })
        .click();
      await expect(page).toHaveURL(/knowledge\/tickets\/ticket-1/);
      expect(ids[0]).toBe(ids[1]);
      await expect(page.getByRole('status')).toContainText([
        'Ticket published',
        'Classification complete',
      ]);
      await expect(
        page.getByRole('heading', { name: 'Retry safely' }),
      ).toBeVisible();
      await expect(
        page.getByText('area:backend', { exact: true }),
      ).toBeVisible();
      await expect(page.locator('pre')).toContainText('return key;');
      await page
        .getByRole('link', { name: 'Capture knowledge', exact: true })
        .click();
      await expect(page).toHaveURL(/knowledge\/new$/);
      await expect(
        page.getByLabel('Title Required', { exact: true }),
      ).toHaveValue('');
      await expect(
        page.getByLabel('Capture Optional', { exact: true }),
      ).toHaveValue('');
      await expect(page.getByLabel('Text 1', { exact: true })).toHaveValue('');
      await expect(page.getByRole('alert')).toHaveCount(0);
      await expect(
        page.getByRole('button', { name: 'Publish ticket', exact: true }),
      ).toBeDisabled();
      await page
        .getByLabel('Title Required', { exact: true })
        .fill('A second insight');
      await page
        .getByLabel('Text 1', { exact: true })
        .fill('A distinct piece of knowledge.');
      await expect(
        page
          .getByRole('complementary')
          .getByRole('button', { name: 'Refine Title', exact: true }),
      ).toBeEnabled();
      await expect(
        page
          .getByRole('complementary')
          .getByRole('button', { name: 'Refine Capture', exact: true }),
      ).toBeDisabled();

      await page
        .getByRole('button', { name: 'Publish ticket', exact: true })
        .click();
      await expect(page).toHaveURL(/knowledge\/tickets\/ticket-2$/);
      await expect(
        page.getByRole('heading', { name: 'A second insight', exact: true }),
      ).toBeVisible();
      expect(published.content.capture).toBe('');
      await expect(page.locator('.knowledge-ticket-capture')).toHaveCount(0);
      expect(ids).toHaveLength(3);
      expect(ids[2]).not.toBe(ids[0]);

      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
    });
  });
}
