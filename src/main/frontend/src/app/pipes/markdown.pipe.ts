import { Pipe, PipeTransform } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';

@Pipe({
  name: 'markdown',
  standalone: true,
  pure: false // Allow re-rendering during streaming
})
export class MarkdownPipe implements PipeTransform {
  constructor(private sanitizer: DomSanitizer) {
    // Configure marked options for streaming-friendly rendering
    marked.setOptions({
      gfm: true, // Use GitHub Flavored Markdown
      breaks: true // Convert \n to <br> for better streaming display
    });
  }

  transform(value: string, isStreaming: boolean = false): SafeHtml {
    if (!value) {
      return '';
    }

    try {
      // During streaming, use simpler rendering to avoid layout shifts
      if (isStreaming) {
        // Just escape HTML and preserve whitespace during streaming
        const escaped = value
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/\n/g, '<br>');
        return this.sanitizer.bypassSecurityTrustHtml(escaped);
      }

      // Full markdown parsing for completed messages
      // Pre-process to fix bold markers with spaces inside
      let processed = value.replace(/\*\*\s+/g, '**');
      processed = processed.replace(/\s+\*\*/g, '**');

      // Parse markdown to HTML
      const html = marked.parse(processed, { async: false }) as string;

      return this.sanitizer.bypassSecurityTrustHtml(html);
    } catch (error) {
      console.error('Error parsing markdown:', error);
      // Return plain text as fallback, escaped for safety
      return this.sanitizer.bypassSecurityTrustHtml(
        value.replace(/</g, '&lt;').replace(/>/g, '&gt;')
      );
    }
  }
}

