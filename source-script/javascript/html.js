import { load } from 'cheerio/slim';

// The delimiter belongs to the source query language, not to quoted CSS attributes.
function parts(rule) {
  const result = [];
  let start = 0;
  let quote = '';
  let depth = 0;
  let escaped = false;
  for (let index = 0; index < rule.length; index++) {
    const character = rule[index];
    if (escaped) { escaped = false; continue; }
    if (character === '\\') { escaped = true; continue; }
    if (quote) {
      if (character === quote) quote = '';
      continue;
    }
    if (character === '"' || character === "'") { quote = character; continue; }
    if (character === '[' || character === '(') depth++;
    if (character === ']' || character === ')') depth--;
    if (depth === 0 && character === '&' && rule[index + 1] === '&') {
      result.push(rule.slice(start, index).trim());
      start = index + 2;
      index++;
    }
  }
  result.push(rule.slice(start).trim());
  return result;
}

function select(html, selectors, all) {
  const $ = load(String(html), { decodeEntities: true }, false);
  let selected = $.root();
  for (let index = 0; index < selectors.length; index++) {
    const selector = selectors[index];
    if (!selector) continue;
    const found = selected.find(selector);
    if (selector === 'body' && found.length === 0 && selected[0]?.type === 'root') continue;
    selected = all && index === selectors.length - 1 ? found : found.first();
  }
  return { $, selected };
}

export function pdfh(html, rule) {
  const selectors = parts(String(rule));
  const output = selectors.pop();
  const { selected } = select(html, selectors, false);
  if (output === 'Text') return selected.text().trim();
  if (output === 'Html') return selected.html() || '';
  return selected.attr(output) || '';
}

export function pdfa(html, rule) {
  const { $, selected } = select(html, parts(String(rule)), true);
  return selected.map((index, element) => $.html(element)).get();
}

export function urljoin(base, relative) {
  if (!base) return String(relative || '');
  if (!relative) return String(base);
  return globalThis.__bananaResolveUrl(String(base), String(relative));
}

export function pd(html, rule, base) {
  let value = pdfh(html, rule).trim();
  const styleUrl = value.match(/url\(\s*(['"]?)(.*?)\1\s*\)/i);
  if (styleUrl) value = styleUrl[2];
  if (!value) return '';
  return urljoin(base, value);
}
