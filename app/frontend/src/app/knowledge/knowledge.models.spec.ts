import {
  detailsLength,
  emptyBlock,
  TicketContent,
  validContent,
} from './knowledge.models';

describe('knowledge content', () => {
  const content = (): TicketContent => ({
    title: 'Title',
    capture: 'Context',
    template: 'Knowledge',
    details: [{ ...emptyBlock('paragraph'), text: 'Details' }],
  });
  it('rejects formatting-only content and enforces all three limits', () => {
    expect(validContent({ ...content(), capture: null })).toBe(true);
    expect(validContent({ ...content(), capture: undefined })).toBe(true);
    for (const blank of ['', ' \n\t', '\u00a0\u200b']) {
      expect(validContent({ ...content(), title: blank })).toBe(false);
      expect(validContent({ ...content(), capture: blank })).toBe(true);
      expect(
        validContent({
          ...content(),
          details: [{ ...emptyBlock('heading'), text: blank }],
        }),
      ).toBe(false);
    }
    expect(validContent({ ...content(), title: 'x'.repeat(201) })).toBe(false);
    expect(validContent({ ...content(), capture: 'x'.repeat(1001) })).toBe(
      false,
    );
    expect(
      validContent({
        ...content(),
        details: [{ ...emptyBlock('paragraph'), text: 'x'.repeat(30001) }],
      }),
    ).toBe(false);
    expect(
      validContent({
        ...content(),
        title: 'x'.repeat(200),
        capture: 'x'.repeat(1000),
        details: [{ ...emptyBlock('paragraph'), text: 'x'.repeat(30000) }],
      }),
    ).toBe(true);
  });
  it('requires nonempty code for Coding and safe complete links', () => {
    expect(validContent({ ...content(), template: 'Coding' })).toBe(false);
    expect(
      validContent({
        ...content(),
        template: 'Coding',
        details: [{ ...emptyBlock('code'), text: 'return key;' }],
      }),
    ).toBe(true);
    expect(
      validContent({
        ...content(),
        details: [
          {
            type: 'link',
            text: 'Click',
            url: 'javascript:alert(1)',
            language: null,
          },
        ],
      }),
    ).toBe(false);
    const linked = {
      ...content(),
      details: [
        {
          type: 'link' as const,
          text: 'Docs',
          url: 'https://example.com',
          language: null,
        },
      ],
    };
    expect(validContent(linked)).toBe(true);
    expect(detailsLength(linked.details)).toBe(23);
  });
});
