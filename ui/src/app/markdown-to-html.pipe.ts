import {Pipe, PipeTransform} from '@angular/core';

/**
 * Converts a subset of Markdown to HTML.
 * Replaces the abandoned markdown-to-html-pipe library (Angular 2-7 only).
 * Handles: headings, ordered/unordered lists, inline code, bold, italic, links.
 */
@Pipe({ name: 'MarkdownToHtml' })
export class MarkdownToHtmlPipe implements PipeTransform {
  transform(value: string): string {
    if (!value) { return ''; }

    const lines = value.split('\n');
    const out: string[] = [];
    let inOl = false;
    let inUl = false;

    for (const raw of lines) {
      const line = raw.replace(/\s+$/, '');

      // Ordered list item
      if (/^\d+\.\s/.test(line)) {
        if (!inOl) { out.push('<ol>'); inOl = true; }
        out.push('<li>' + this.inline(line.replace(/^\d+\.\s/, '')) + '</li>');
        continue;
      }
      if (inOl) { out.push('</ol>'); inOl = false; }

      // Unordered list item
      if (/^[-*]\s/.test(line)) {
        if (!inUl) { out.push('<ul>'); inUl = true; }
        out.push('<li>' + this.inline(line.replace(/^[-*]\s/, '')) + '</li>');
        continue;
      }
      if (inUl) { out.push('</ul>'); inUl = false; }

      // Headings
      const heading = line.match(/^(#{1,6})\s+(.*)/);
      if (heading) {
        const level = heading[1].length;
        out.push(`<h${level}>${this.inline(heading[2])}</h${level}>`);
        continue;
      }

      // Blank line → paragraph break
      if (line.trim() === '') {
        out.push('<br>');
        continue;
      }

      out.push('<p>' + this.inline(line) + '</p>');
    }

    if (inOl) { out.push('</ol>'); }
    if (inUl) { out.push('</ul>'); }

    return out.join('\n');
  }

  private inline(text: string): string {
    return text
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\*([^*]+)\*/g, '<em>$1</em>')
      .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');
  }
}
