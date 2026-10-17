export type BlockType =
  | 'paragraph'
  | 'heading'
  | 'bulletList'
  | 'orderedList'
  | 'link'
  | 'code';
export interface DetailBlock {
  type: BlockType;
  text: string;
  language: string | null;
  url: string | null;
}
export interface TicketContent {
  title: string;
  capture?: string | null;
  template: string;
  details: DetailBlock[];
}
export interface Ticket {
  id: string;
  content: TicketContent;
  authorId: string;
  authorName: string;
  createdAt: string;
  classificationStatus: string;
  attributes: string[];
}
export type RefinementField = 'title' | 'capture';
export interface Refinement {
  field: RefinementField;
  text: string;
}
export const templates = [
  'Knowledge',
  'Coding',
  'Problem',
  'Decision',
  'Accomplishment',
  'Learning',
];
export const languages = [
  'plaintext',
  'java',
  'typescript',
  'javascript',
  'python',
  'sql',
  'bash',
  'json',
  'yaml',
  'html',
  'css',
  'csharp',
  'cpp',
  'go',
  'rust',
  'kotlin',
];
export const blockTypes: BlockType[] = [
  'paragraph',
  'heading',
  'bulletList',
  'orderedList',
  'link',
  'code',
];
export const emptyBlock = (type: BlockType): DetailBlock => ({
  type,
  text: '',
  language: type === 'code' ? 'typescript' : null,
  url: type === 'link' ? '' : null,
});
export const detailsLength = (blocks: DetailBlock[]) =>
  blocks.reduce((n, b) => n + b.text.length + (b.url?.length ?? 0), 0);
const meaningful = (text: string) => !!text.replace(/[\s\p{Cf}]/gu, '');
export function validContent(content: TicketContent): boolean {
  return (
    meaningful(content.title) &&
    content.title.length <= 200 &&
    (content.capture?.length ?? 0) <= 1000 &&
    templates.includes(content.template) &&
    validDetails(content)
  );
}
export function validDetails(content: TicketContent): boolean {
  return (
    content.details.length > 0 &&
    content.details.length <= 200 &&
    detailsLength(content.details) <= 30000 &&
    content.details.some((b) => meaningful(b.text)) &&
    (content.template !== 'Coding' ||
      content.details.some((b) => b.type === 'code' && meaningful(b.text))) &&
    content.details.every(
      (b) => b.type !== 'link' || (meaningful(b.text) && safeUrl(b.url)),
    )
  );
}
function safeUrl(value: string | null): boolean {
  try {
    const url = new URL(value ?? '');
    return (
      ['http:', 'https:'].includes(url.protocol) &&
      !url.username &&
      !url.password
    );
  } catch {
    return false;
  }
}
