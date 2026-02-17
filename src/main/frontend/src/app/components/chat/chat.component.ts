import { Component, signal, effect, ViewChild, ElementRef, computed } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';

import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule, MatIconRegistry } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MarkdownComponent } from 'ngx-markdown';
import { ChatService, ChatMessage, HealthInfo } from '../../services/chat.service';
import { PdfService, PdfExtractResult } from '../../services/pdf.service';
import { ActivityPanelComponent } from '../activity-panel/activity-panel.component';
import { ConfigPanelComponent } from '../config-panel/config-panel.component';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatChipsModule,
    MatProgressBarModule,
    MarkdownComponent,
    ActivityPanelComponent,
    ConfigPanelComponent
  ],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss'
})
export class ChatComponent {
  @ViewChild('messagesContainer') private messagesContainer!: ElementRef;
  
  protected messages = signal<ChatMessage[]>([]);
  protected userInput = signal('');
  protected isStreaming = signal(false);
  protected healthInfo = signal<HealthInfo | null>(null);
  protected sessionId = signal<string | null>(null);
  protected isCreatingSession = signal(false);
  protected activityPanelCollapsed = signal(false);
  protected configPanelCollapsed = signal(false);
  private retryCount = 0;
  private static readonly MAX_RETRIES = 3;
  private static readonly RETRY_DELAY_MS = 5000;
  protected importedPdf = signal<PdfExtractResult | null>(null);
  protected isImportingPdf = signal(false);
  protected ragEnabled = signal(false);
  protected documentIngested = signal(false);
  #fileInput: HTMLInputElement | null = null;

  // Expose activities and todos from the service
  protected activities = computed(() => this.chatService.activities());
  protected todos = computed(() => this.chatService.todos());

  constructor(
    private chatService: ChatService,
    private pdfService: PdfService,
    private snackBar: MatSnackBar,
    private matIconRegistry: MatIconRegistry,
    private domSanitizer: DomSanitizer
  ) {
    // Register custom factory icon
    this.matIconRegistry.addSvgIcon(
      'factory',
      this.domSanitizer.bypassSecurityTrustResourceUrl('/factory-icon.svg')
    );

    // Check Goose availability and RAG status on init
    this.chatService.checkHealth().then(health => {
      this.healthInfo.set(health);
    });
    this.chatService.checkRagStatus().then(status => {
      this.ragEnabled.set(status.ragEnabled);
      this.documentIngested.set(status.hasDocuments);
    });

    // Auto-scroll when messages update with smooth animation
    effect(() => {
      this.messages();
      // Use requestAnimationFrame for smoother scroll animation
      requestAnimationFrame(() => {
        setTimeout(() => this.scrollToBottom(), 50);
      });
    });

    // Automatically create a session on component init if Goose is available
    effect(() => {
      const health = this.healthInfo();
      if (health?.available && !this.sessionId() && !this.isCreatingSession()) {
        this.startNewConversation();
      }
    });
  }

  protected get gooseAvailable(): boolean {
    return this.healthInfo()?.available ?? false;
  }

  protected get gooseVersion(): string {
    return this.healthInfo()?.version ?? 'unknown';
  }

  protected get gooseProvider(): string {
    return this.healthInfo()?.provider ?? 'unknown';
  }

  protected get gooseModel(): string {
    return this.healthInfo()?.model ?? 'unknown';
  }

  protected get gooseMessage(): string {
    return this.healthInfo()?.message ?? '';
  }

  protected get gooseModelSource(): string {
    return this.healthInfo()?.source ?? 'unknown';
  }

  protected get isGenaiService(): boolean {
    return this.healthInfo()?.source === 'genai-service';
  }

  /**
   * Start a new conversation session
   */
  protected async startNewConversation(): Promise<void> {
    if (this.isCreatingSession()) {
      return;
    }

    this.isCreatingSession.set(true);

    try {
      // Close existing session if any
      const currentSessionId = this.sessionId();
      if (currentSessionId) {
        await this.chatService.closeSession(currentSessionId);
      }

      // Create new session
      const newSessionId = await this.chatService.createSession();
      this.sessionId.set(newSessionId);
      
      // Clear messages and todos for new conversation
      this.messages.set([]);
      this.chatService.clearTodos();
      this.chatService.clearActivities();
      
      this.snackBar.open('New Tanzu-Factory conversation started', 'Close', {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
      
      console.log('Started new conversation with session:', newSessionId);
    } catch (error) {
      console.error('Failed to start new conversation:', error);
      this.snackBar.open('Failed to start conversation', 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
      this.sessionId.set(null);
    } finally {
      this.isCreatingSession.set(false);
    }
  }

  /**
   * End the current conversation
   */
  protected async endConversation(): Promise<void> {
    const currentSessionId = this.sessionId();
    if (!currentSessionId) {
      return;
    }

    try {
      await this.chatService.closeSession(currentSessionId);
      this.sessionId.set(null);
      this.messages.set([]);
      this.chatService.clearTodos();
      this.chatService.clearActivities();
      
      this.snackBar.open('Conversation ended', 'Close', {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    } catch (error) {
      console.error('Failed to end conversation:', error);
      this.snackBar.open('Failed to end conversation', 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    }
  }

  protected toggleActivityPanel(): void {
    this.activityPanelCollapsed.update(v => !v);
  }

  protected toggleConfigPanel(): void {
    this.configPanelCollapsed.update(v => !v);
  }

  protected sendMessage(): void {
    const rawPrompt = this.userInput().trim();
    const hasPdf = !!this.importedPdf()?.text;
    const prompt = rawPrompt || (hasPdf ? 'Please summarize or analyze the attached document.' : '');
    const currentSessionId = this.sessionId();

    if (!prompt || this.isStreaming() || !currentSessionId) {
      return;
    }

    // Clear activities for new message
    this.chatService.clearActivities();
    
    // Auto-expand activity panel when streaming starts
    this.activityPanelCollapsed.set(false);

    // Add user message
    const userMessage: ChatMessage = {
      role: 'user',
      content: prompt,
      timestamp: new Date()
    };
    this.messages.update(msgs => [...msgs, userMessage]);
    this.userInput.set('');
    this.isStreaming.set(true);

    // Add assistant message placeholder
    const assistantMessage: ChatMessage = {
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      streaming: true
    };
    this.messages.update(msgs => [...msgs, assistantMessage]);

    // When RAG is active the backend handles retrieval; only fall back to inline context when RAG is unavailable
    const documentContext = (this.importedPdf()?.text && !this.ragEnabled()) ? this.importedPdf()!.text : undefined;
    // Stream the response (POST when inline document context is needed, GET otherwise)
    this.chatService.sendMessage(prompt, currentSessionId, documentContext).subscribe({
      next: (token: string) => {
        // Append token directly - no newline needed for token-level streaming
        this.messages.update(msgs => {
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg.role === 'assistant') {
            return [
              ...msgs.slice(0, -1),
              {
                ...lastMsg,
                content: lastMsg.content + token
              }
            ];
          }
          return msgs;
        });
      },
      error: (error) => {
        console.error('Chat error:', error);
        
        const errorMessage = error.message || 'Failed to get response from Tanzu-Factory.';
        
        // Handle retriable errors:
        // __RETRY__ = server-initiated (cold start / MCP initialization)
        // __CONNECTION_DROPPED__ = proxy dropped the SSE connection mid-stream
        const isRetriable = errorMessage === '__RETRY__' || errorMessage === '__CONNECTION_DROPPED__';
        if (isRetriable && this.retryCount < ChatComponent.MAX_RETRIES) {
          this.retryCount++;
          const reason = errorMessage === '__CONNECTION_DROPPED__' ? 'Connection lost, reconnecting' : 'Initializing AI agent';
          console.log(`[Retry] ${reason} — attempt ${this.retryCount}/${ChatComponent.MAX_RETRIES} in ${ChatComponent.RETRY_DELAY_MS}ms`);
          
          this.messages.update(msgs => {
            const lastMsg = msgs[msgs.length - 1];
            if (lastMsg.role === 'assistant') {
              return [
                ...msgs.slice(0, -1),
                { ...lastMsg, content: `${reason}... (attempt ${this.retryCount + 1})`, streaming: true }
              ];
            }
            return msgs;
          });

          setTimeout(() => {
            this.isStreaming.set(false);
            this.userInput.set(prompt);
            this.messages.update(msgs => msgs.slice(0, -1)); // remove placeholder
            this.sendMessage();
          }, ChatComponent.RETRY_DELAY_MS);
          return;
        }
        
        // Check if session expired
        if (errorMessage.includes('Session not found') || errorMessage.includes('expired')) {
          this.snackBar.open('Session expired. Starting new conversation...', 'Close', {
            duration: 3000,
            horizontalPosition: 'center',
            verticalPosition: 'bottom'
          });
          
          // Remove the failed assistant message
          this.messages.update(msgs => msgs.slice(0, -1));
          
          // Start new conversation and resend message
          this.startNewConversation().then(() => {
            // Resend the message after new session is created
            if (this.sessionId()) {
              this.userInput.set(prompt);
              setTimeout(() => this.sendMessage(), 500);
            }
          });
        } else {
          this.messages.update(msgs => {
            const lastMsg = msgs[msgs.length - 1];
            if (lastMsg.role === 'assistant' && lastMsg.streaming) {
              return [
                ...msgs.slice(0, -1),
                {
                  ...lastMsg,
                  content: lastMsg.content || `Error: ${errorMessage}`,
                  streaming: false
                }
              ];
            }
            return msgs;
          });
        }
        
        this.isStreaming.set(false);
        this.retryCount = 0;
      },
      complete: () => {
        this.messages.update(msgs => {
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg.role === 'assistant') {
            return [
              ...msgs.slice(0, -1),
              {
                ...lastMsg,
                streaming: false
              }
            ];
          }
          return msgs;
        });
        this.isStreaming.set(false);
        this.retryCount = 0;
        // Clear imported PDF after send so it is not re-sent with next message
        this.clearImportedPdf();
      }
    });
  }

  protected onKeyPress(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  protected onInputChange(event: Event): void {
    const target = event.target as HTMLTextAreaElement;
    this.userInput.set(target.value);
  }

  /** Trigger hidden file input for PDF import */
  protected triggerPdfImport(): void {
    if (!this.#fileInput) {
      this.#fileInput = document.createElement('input');
      this.#fileInput.type = 'file';
      this.#fileInput.accept = 'application/pdf,.pdf';
      this.#fileInput.style.display = 'none';
      this.#fileInput.addEventListener('change', (e: Event) => this.onPdfFileSelected(e));
    }
    this.#fileInput.value = '';
    this.#fileInput.click();
  }

  private async onPdfFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    this.isImportingPdf.set(true);
    try {
      const result = await this.pdfService.extractTextFromFile(file);
      this.importedPdf.set(result);

      // If RAG is available, upload to backend for vectorization
      if (this.ragEnabled()) {
        try {
          await this.chatService.ingestDocument(result.filename, result.text);
          this.documentIngested.set(true);
          this.snackBar.open(
            `"${result.filename}" indexed for search (${result.pageCount} page(s))`,
            'Close',
            { duration: 4000, horizontalPosition: 'center', verticalPosition: 'bottom' }
          );
        } catch (err) {
          console.error('Document ingestion failed, will use inline context', err);
          this.snackBar.open(
            `Imported "${result.filename}" (vector store unavailable, using inline context)`,
            'Close',
            { duration: 4000, horizontalPosition: 'center', verticalPosition: 'bottom' }
          );
        }
      } else {
        this.snackBar.open(
          `Imported "${result.filename}" (${result.pageCount} page(s), ${result.text.length} chars)`,
          'Close',
          { duration: 4000, horizontalPosition: 'center', verticalPosition: 'bottom' }
        );
      }
    } catch (err) {
      console.error('PDF import failed', err);
      this.snackBar.open('Failed to extract text from PDF. The file may be image-only or corrupted.', 'Close', {
        duration: 5000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    } finally {
      this.isImportingPdf.set(false);
    }
  }

  protected clearImportedPdf(): void {
    this.importedPdf.set(null);
  }

  private scrollToBottom(): void {
    if (this.messagesContainer) {
      const element = this.messagesContainer.nativeElement;
      // Smooth scroll to bottom
      element.scrollTo({
        top: element.scrollHeight,
        behavior: 'smooth'
      });
    }
  }
}

