import { Injectable } from '@angular/core';
import { getDocument, GlobalWorkerOptions } from 'pdfjs-dist';

// PDF.js worker must be loaded from a URL (browser). Point to the asset copied to public/.
if (typeof window !== 'undefined') {
  GlobalWorkerOptions.workerSrc = '/pdf.worker.min.mjs';
}

export interface PdfExtractResult {
  filename: string;
  text: string;
  pageCount: number;
}

@Injectable({
  providedIn: 'root'
})
export class PdfService {
  /**
   * Extract text from a PDF file (e.g. from an input file).
   * Works for text-based PDFs; scanned/image-only PDFs may return little or no text.
   */
  async extractTextFromFile(file: File): Promise<PdfExtractResult> {
    const arrayBuffer = await file.arrayBuffer();
    const pdf = await getDocument({ data: arrayBuffer }).promise;
    const pageCount = pdf.numPages;
    const textParts: string[] = [];

    for (let i = 1; i <= pageCount; i++) {
      const page = await pdf.getPage(i);
      const content = await page.getTextContent();
      const pageText = content.items
        .map(item => ('str' in item ? (item as { str: string }).str : ''))
        .join(' ');
      textParts.push(pageText);
    }

    const text = textParts.join('\n\n').replace(/\s+/g, ' ').trim();
    return { filename: file.name, text, pageCount };
  }
}
