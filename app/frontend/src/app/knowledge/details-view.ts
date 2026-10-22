import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
import { DetailBlock } from './knowledge.models';
import hljs from 'highlight.js/lib/core';
import java from 'highlight.js/lib/languages/java';
import typescript from 'highlight.js/lib/languages/typescript';
import javascript from 'highlight.js/lib/languages/javascript';
import python from 'highlight.js/lib/languages/python';
import sql from 'highlight.js/lib/languages/sql';
import bash from 'highlight.js/lib/languages/bash';
import json from 'highlight.js/lib/languages/json';
import yaml from 'highlight.js/lib/languages/yaml';
import xml from 'highlight.js/lib/languages/xml';
import css from 'highlight.js/lib/languages/css';
import csharp from 'highlight.js/lib/languages/csharp';
import cpp from 'highlight.js/lib/languages/cpp';
import go from 'highlight.js/lib/languages/go';
import rust from 'highlight.js/lib/languages/rust';
import kotlin from 'highlight.js/lib/languages/kotlin';
for (const [name, grammar] of Object.entries({
  java,
  typescript,
  javascript,
  python,
  sql,
  bash,
  json,
  yaml,
  html: xml,
  css,
  csharp,
  cpp,
  go,
  rust,
  kotlin,
}))
  hljs.registerLanguage(name, grammar);
@Component({
  selector: 'app-details-view',
  templateUrl: './details-view.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetailsView {
  readonly blocks = input.required<DetailBlock[]>();
  readonly rendered = computed(() =>
    this.blocks().map((block) => ({
      ...block,
      highlighted:
        block.type === 'code' && hljs.getLanguage(block.language ?? '')
          ? hljs.highlight(block.text, { language: block.language! }).value
          : null,
    })),
  );
  lines(text: string) {
    return text.split('\n').filter((line) => !!line.trim());
  }
}
