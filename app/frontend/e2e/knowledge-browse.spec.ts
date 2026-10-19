import { expect, test } from '@playwright/test';

const account = {
  id: 'author',
  organizationId: 'org',
  fullName: 'Ada',
  organizationName: 'Acme',
  accessRole: 'MEMBER',
};
const summary = (i: number, detailsType = 'Coding') => ({
  id: `ticket-${i}`,
  title: `Reliable retries ${i}: keeping context for the whole organization`,
  capturePreview:
    'Reuse the original key. Keep a compact explanation of the context and the outcome so your colleagues can apply it.',
  detailsType,
  authorName: 'Ada Lovelace',
  createdAt: '2026-10-18T12:30:00Z',
  classificationStatus: 'COMPLETE',
  attributes: [
    'technology:java',
    'area:backend',
    'type:coding',
    'area:testing',
  ],
});
for (const width of [1440, 390]) {
  test(`browses, filters and restores knowledge at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    let moreFails = true;
    let calls = 0;
    await page.route('**/api/**', async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/api/auth/me')
        return route.fulfill({ json: { data: account } });
      if (url.pathname === '/api/knowledge/tickets') {
        calls++;
        const type = url.searchParams.get('detailsType');
        if (type === 'Learning')
          return route.fulfill({
            json: { data: { items: [], nextCursor: null } },
          });
        if (url.searchParams.has('cursor')) {
          if (moreFails) return route.fulfill({ status: 503, json: {} });
          return route.fulfill({
            json: {
              data: {
                items: [summary(21, type ?? 'Coding')],
                nextCursor: null,
              },
            },
          });
        }
        return route.fulfill({
          json: {
            data: {
              items: Array.from({ length: 20 }, (_, i) =>
                summary(i + 1, type ?? 'Coding'),
              ),
              nextCursor: 'next',
            },
          },
        });
      }
      if (url.pathname.startsWith('/api/knowledge/tickets/'))
        return route.fulfill({
          json: {
            data: {
              ...summary(21),
              id: 'ticket-21',
              content: {
                title: 'Reliable retries 21',
                capture: 'Reuse the same key.',
                template: 'Coding',
                details: [
                  { type: 'heading', text: 'Keep the context' },
                  { type: 'bulletList', text: 'First\nSecond' },
                  {
                    type: 'link',
                    text: 'Documentation',
                    url: 'https://example.com',
                  },
                  {
                    type: 'code',
                    language: 'java',
                    text: 'return "a sufficiently long code example that stays readable on narrow screens";',
                  },
                ],
              },
            },
          },
        });
      return route.fulfill({ status: 404 });
    });
    await page.goto('/knowledge');
    await expect(page.locator('.knowledge-list-row')).toHaveCount(20);
    await expect(
      page
        .locator('.knowledge-list-row')
        .first()
        .locator('.knowledge-attribute'),
    ).toHaveText(['Coding', 'Backend', 'Testing']);
    await expect(page.locator('.knowledge-list-row').first()).toContainText(
      '+1 more',
    );
    await page
      .getByLabel('Details type', { exact: true })
      .selectOption('Coding');
    await expect(page.locator('.knowledge-list-row')).toHaveCount(20);
    await page.getByRole('button', { name: 'Load more', exact: true }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.locator('.knowledge-list-row')).toHaveCount(20);
    moreFails = false;
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await expect(page.locator('.knowledge-list-row')).toHaveCount(21);
    await expect(
      page.getByRole('button', { name: 'Load more', exact: true }),
    ).toHaveCount(0);
    await page
      .getByRole('link', { name: /^Reliable retries 21:/ })
      .scrollIntoViewIfNeeded();
    const position = await page.evaluate(() => window.scrollY);
    const fetched = calls;
    await page.getByRole('link', { name: /^Reliable retries 21:/ }).click();
    await expect(
      page.getByRole('heading', { name: 'Reliable retries 21', exact: true }),
    ).toBeVisible();
    await expect(page.locator('pre')).toContainText('return');
    await expect(
      page.getByRole('link', { name: 'Documentation' }),
    ).toHaveAttribute('rel', 'noopener noreferrer');
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.getByRole('link', { name: 'Back to knowledge' }).click();
    await expect(page.locator('.knowledge-list-row')).toHaveCount(21);
    await expect(page.getByLabel('Details type', { exact: true })).toHaveValue(
      'Coding',
    );
    await expect
      .poll(() => page.evaluate(() => window.scrollY))
      .toBeCloseTo(position, -1);
    expect(calls).toBe(fetched);
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await expect(page.locator('.knowledge-list-row')).toHaveCount(20);
    await expect(page.getByLabel('Details type', { exact: true })).toHaveValue(
      'Coding',
    );
    await page
      .getByLabel('Details type', { exact: true })
      .selectOption('Learning');
    await expect(
      page.getByRole('heading', { name: 'No tickets of this type yet' }),
    ).toBeVisible();
    await page
      .getByRole('button', { name: 'Clear filter', exact: true })
      .first()
      .click();
    await expect(page.locator('.knowledge-list-row')).toHaveCount(20);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  });
}

test('detail polling preserves content on failure and clears it after access denial', async ({
  page,
}) => {
  let reads = 0;
  await page.clock.install();
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/auth/me')
      return route.fulfill({ json: { data: account } });
    if (path.startsWith('/api/knowledge/tickets/')) {
      reads++;
      if (reads === 2) return route.fulfill({ status: 503, json: {} });
      if (reads > 2)
        return route.fulfill({
          status: 404,
          json: {
            error: {
              message: 'This ticket is unavailable in your organization.',
            },
          },
        });
      return route.fulfill({
        json: {
          data: {
            ...summary(1),
            classificationStatus: 'PENDING',
            attributes: [],
            content: {
              title: 'Keep this content',
              capture: '',
              details: [
                {
                  type: 'paragraph',
                  text: 'Published details remain available.',
                },
              ],
            },
          },
        },
      });
    }
    return route.fulfill({ status: 404 });
  });
  await page.goto('/knowledge/tickets/ticket-1');
  await expect(
    page.getByRole('heading', { name: 'Keep this content' }),
  ).toBeVisible();
  await expect(page.getByRole('status')).toContainText('Classifying');
  await page.clock.fastForward(5000);
  await expect(page.getByRole('alert')).toContainText('could not be refreshed');
  await expect(
    page.getByRole('heading', { name: 'Keep this content' }),
  ).toBeVisible();
  await page.clock.fastForward(5000);
  await expect(
    page.getByRole('heading', { name: 'Keep this content' }),
  ).toHaveCount(0);
  await expect(page.getByRole('alert')).toContainText('unavailable');
  await page.clock.fastForward(15000);
  expect(reads).toBe(3);
});
