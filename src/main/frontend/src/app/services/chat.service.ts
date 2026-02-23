import { Injectable, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  streaming?: boolean;
  /** Signals that Goose produced no output and the client should retry */
  retryRequested?: boolean;
}

export interface SessionInfo {
  sessionId: string;
  createdAt: Date;
  provider?: string;
  model?: string;
}

export interface HealthInfo {
  available: boolean;
  version: string;
  provider: string;
  model: string;
  /** Source of model configuration: "genai-service" or "environment" */
  source?: string;
  message?: string;
}

export interface ActivityEvent {
  id: string;
  timestamp: Date;
  type: 'tool_request' | 'tool_response' | 'notification';
  toolName?: string;
  extensionId?: string;
  arguments?: Record<string, unknown>;
  status: 'running' | 'completed' | 'error' | 'info';
  message?: string;
}

export interface TodoItem {
  id: string;
  content: string;
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled';
}

export interface SkillInfo {
  name: string;
  description?: string;
  /** Source type: "inline", "file", or "git" */
  source: string;
  path?: string;
  repository?: string;
  branch?: string;
}

export interface McpServerInfo {
  name: string;
  /** Transport type: "stdio" or "streamable_http" */
  type: string;
  url?: string;
  command?: string;
  args?: string[];
  /** Whether this server requires OAuth authentication */
  requiresAuth?: boolean;
  /** Whether the user is currently authenticated with this server */
  authenticated?: boolean;
}

export interface GooseConfig {
  provider?: string;
  model?: string;
  skills: SkillInfo[];
  mcpServers: McpServerInfo[];
  error?: string;
}

@Injectable({
  providedIn: 'root'
})
export class ChatService {
  private readonly apiUrl = '/api/chat';
  private currentSession = signal<SessionInfo | null>(null);
  
  // Activity events stream - emits tool calls and notifications
  private activitySubject = new Subject<ActivityEvent>();
  readonly activities$ = this.activitySubject.asObservable();
  
  // Signal to track current activities for the activity panel
  private _activities = signal<ActivityEvent[]>([]);
  readonly activities = this._activities.asReadonly();
  
  // Signal to track todo items parsed from todo__todo_write tool calls
  private _todos = signal<TodoItem[]>([]);
  readonly todos = this._todos.asReadonly();

  /**
   * Get the current active session
   */
  getCurrentSession(): SessionInfo | null {
    return this.currentSession();
  }

  /**
   * Clear all activities (typically when starting a new message).
   * Note: Todos are NOT cleared here - they persist across messages within a session
   * and are only updated when a new todo_write event arrives.
   */
  clearActivities(): void {
    this._activities.set([]);
  }
  
  /**
   * Clear todos (called when starting a new session)
   */
  clearTodos(): void {
    this._todos.set([]);
  }

  /**
   * Parse markdown checklist content from todo__todo_write into TodoItem array.
   * Handles formats:
   * - [ ] pending task
   * - [x] completed task  
   * - [-] in-progress or cancelled task
   */
  private parseTodoContent(content: string): TodoItem[] {
    const lines = content.split('\n');
    const todos: TodoItem[] = [];
    
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed.startsWith('-')) continue;
      
      // Match patterns: - [ ] text, - [x] text, - [-] text
      const match = trimmed.match(/^-\s*\[(.)\]\s*(.+)$/);
      if (match) {
        const marker = match[1].toLowerCase();
        const taskContent = match[2].trim();
        
        let status: TodoItem['status'];
        if (marker === 'x') {
          status = 'completed';
        } else if (marker === '-') {
          status = 'in_progress';
        } else {
          status = 'pending';
        }
        
        todos.push({
          id: `todo-${todos.length}`,
          content: taskContent,
          status
        });
      }
    }
    
    return todos;
  }

  /**
   * Create a new conversation session
   */
  async createSession(
    provider?: string, 
    model?: string,
    timeoutMinutes?: number
  ): Promise<string> {
    const body: Record<string, unknown> = {};
    if (provider) body['provider'] = provider;
    if (model) body['model'] = model;
    if (timeoutMinutes) body['sessionInactivityTimeoutMinutes'] = timeoutMinutes;

    const response = await fetch(`${this.apiUrl}/sessions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: Object.keys(body).length > 0 ? JSON.stringify(body) : undefined
    });

    if (!response.ok) {
      throw new Error(`Failed to create session: ${response.status}`);
    }

    const result = await response.json();
    if (!result.success || !result.sessionId) {
      throw new Error(result.message || 'Failed to create session');
    }

    this.currentSession.set({
      sessionId: result.sessionId,
      createdAt: new Date(),
      provider,
      model
    });

    console.log('Created new Goose session:', result.sessionId);
    return result.sessionId;
  }

  /**
   * Send a message to the current session with SSE streaming.
   * When documentContext is provided (e.g. imported PDF text), uses POST to avoid URL length limits.
   *
   * SSE Events handled:
   * - `token`: Individual tokens (emitted to observer)
   * - `complete`: Stream completion with token count
   * - `status`: Processing status messages (logged)
   * - `error`: Error events
   */
  sendMessage(message: string, sessionId: string, documentContext?: string): Observable<string> {
    const usePost = documentContext != null && documentContext.trim().length > 0;

    if (usePost) {
      return this.sendMessagePost(message, sessionId, documentContext);
    }

    return new Observable(observer => {
      const encodedMessage = encodeURIComponent(message);
      const url = `${this.apiUrl}/sessions/${sessionId}/stream?message=${encodedMessage}`;
      const eventSource = new EventSource(url);

      this.attachEventSourceHandlers(eventSource, observer);
      return () => {
        console.log('[SSE] Closing connection');
        eventSource.close();
      };
    });
  }

  /**
   * Send message with document context via POST and parse SSE from response body.
   */
  private sendMessagePost(message: string, sessionId: string, documentContext: string): Observable<string> {
    return new Observable(observer => {
      const url = `${this.apiUrl}/sessions/${sessionId}/stream`;
      const body = JSON.stringify({ message, documentContext: documentContext.trim() });
      let aborted = false;

      fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body
      }).then(response => {
        if (!response.ok) {
          throw new Error(`Stream request failed: ${response.status}`);
        }
        const reader = response.body?.getReader();
        const decoder = new TextDecoder();
        if (!reader) {
          observer.complete();
          return;
        }

        let buffer = '';
        let eventType = '';
        const processChunk = (): Promise<void> =>
          reader.read().then(({ done, value }) => {
            if (aborted || done) {
              if (done) observer.complete();
              return;
            }
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split(/\n/);
            buffer = lines.pop() ?? '';

            for (const line of lines) {
              if (line.startsWith('event:')) {
                eventType = line.slice(6).trim();
              } else if (line.startsWith('data:') && eventType) {
                const data = line.slice(5).trim();
                this.handleStreamEvent(eventType, data, observer);
                eventType = '';
              }
            }
            return processChunk();
          });

        processChunk().catch(err => {
          if (!aborted) observer.error(err);
        });
      }).catch(err => {
        if (!aborted) observer.error(err);
      });

      return () => {
        aborted = true;
      };
    });
  }

  private handleStreamEvent(eventType: string, data: string, observer: { next: (t: string) => void; error: (e: Error) => void; complete: () => void }): void {
    switch (eventType) {
      case 'token':
        if (data && data.length > 0) {
          try {
            const token = JSON.parse(data);
            if (token && token.length > 0) observer.next(token);
          } catch {
            observer.next(data);
          }
        }
        break;
      case 'activity':
        try {
          const activityData = JSON.parse(data);
          const activity: ActivityEvent = {
            id: activityData.id,
            timestamp: new Date(activityData.timestamp || Date.now()),
            type: activityData.type,
            toolName: activityData.toolName,
            extensionId: activityData.extensionId,
            arguments: activityData.arguments,
            status: activityData.status,
            message: activityData.message
          };
          const isTodoWrite = activity.toolName === 'todo_write' && activity.extensionId === 'todo';
          if (isTodoWrite && activity.arguments?.['content']) {
            const todoContent = activity.arguments['content'] as string;
            this._todos.set(this.parseTodoContent(todoContent));
          } else {
            this._activities.update(activities => {
              if (activity.type === 'tool_response') {
                const existing = activities.find(a => a.id === activity.id);
                if (existing?.toolName === 'todo_write') return activities;
                return activities.map(a => (a.id === activity.id ? { ...a, status: activity.status } : a));
              }
              return [...activities, activity];
            });
          }
          this.activitySubject.next(activity);
        } catch (e) {
          console.warn('Failed to parse activity event:', data, e);
        }
        break;
      case 'retry':
        console.warn('[SSE] Server requested retry:', data);
        observer.error(new Error('__RETRY__'));
        break;
      case 'complete':
        observer.complete();
        break;
      case 'error':
        observer.error(new Error(data || 'Stream error'));
        break;
      default:
        break;
    }
  }

  private attachEventSourceHandlers(
    eventSource: EventSource,
    observer: { next: (t: string) => void; error: (e: Error) => void; complete: () => void }
  ): void {
    // Track whether the server sent a proper 'complete' event.
    // If the connection drops without one, we treat it as a retriable error
    // rather than silently completing with an empty/truncated response.
    let serverCompletedCleanly = false;

    eventSource.addEventListener('token', (event: MessageEvent) => {
      const data = event.data;
      if (data && data.length > 0) {
        try {
          const token = JSON.parse(data);
          if (token && token.length > 0) observer.next(token);
        } catch {
          observer.next(data);
        }
      }
    });

    eventSource.addEventListener('activity', (event: MessageEvent) => {
      try {
        const activityData = JSON.parse(event.data);
        const activity: ActivityEvent = {
          id: activityData.id,
          timestamp: new Date(activityData.timestamp || Date.now()),
          type: activityData.type,
          toolName: activityData.toolName,
          extensionId: activityData.extensionId,
          arguments: activityData.arguments,
          status: activityData.status,
          message: activityData.message
        };
        const isTodoWrite = activity.toolName === 'todo_write' && activity.extensionId === 'todo';
        if (isTodoWrite && activity.arguments?.['content']) {
          const todoContent = activity.arguments['content'] as string;
          this._todos.set(this.parseTodoContent(todoContent));
        } else {
          this._activities.update(activities => {
            if (activity.type === 'tool_response') {
              const existing = activities.find(a => a.id === activity.id);
              if (existing?.toolName === 'todo_write') return activities;
              return activities.map(a => (a.id === activity.id ? { ...a, status: activity.status } : a));
            }
            return [...activities, activity];
          });
        }
        this.activitySubject.next(activity);
      } catch (e) {
        console.warn('Failed to parse activity event:', event.data, e);
      }
    });

    eventSource.addEventListener('retry', (event: MessageEvent) => {
      console.warn('[SSE] Server requested retry:', event.data);
      eventSource.close();
      observer.error(new Error('__RETRY__'));
    });

    eventSource.addEventListener('complete', () => {
      serverCompletedCleanly = true;
      eventSource.close();
      observer.complete();
    });

    eventSource.addEventListener('error', (event: MessageEvent) => {
      eventSource.close();
      observer.error(new Error(event.data || 'Stream error'));
    });

    eventSource.onerror = () => {
      if (eventSource.readyState === EventSource.CLOSED) {
        if (serverCompletedCleanly) {
          observer.complete();
        } else {
          console.warn('[SSE] Connection closed without server complete event — likely proxy timeout');
          observer.error(new Error('__CONNECTION_DROPPED__'));
        }
      } else {
        eventSource.close();
        observer.error(new Error('Connection to server failed'));
      }
    };
  }

  /**
   * Close the current conversation session
   */
  async closeSession(sessionId: string): Promise<void> {
    try {
      const response = await fetch(`${this.apiUrl}/sessions/${sessionId}`, {
        method: 'DELETE'
      });

      if (!response.ok) {
        console.warn(`Failed to close session ${sessionId}: ${response.status}`);
      }

      const currentSessionInfo = this.currentSession();
      if (currentSessionInfo && currentSessionInfo.sessionId === sessionId) {
        this.currentSession.set(null);
      }

      console.log('Closed Goose session:', sessionId);
    } catch (error) {
      console.error('Error closing session:', error);
      throw error;
    }
  }

  /**
   * Check if a session is active
   */
  async checkSessionStatus(sessionId: string): Promise<boolean> {
    try {
      const response = await fetch(`${this.apiUrl}/sessions/${sessionId}/status`);
      if (!response.ok) {
        return false;
      }
      const result = await response.json();
      return result.active;
    } catch (error) {
      console.error('Error checking session status:', error);
      return false;
    }
  }

  /**
   * Check Goose health status
   */
  checkHealth(): Promise<HealthInfo> {
    return fetch(`${this.apiUrl}/health`)
      .then(response => response.json())
      .catch(error => {
        console.error('Health check failed:', error);
        return { 
          available: false, 
          version: 'unknown',
          provider: 'unknown',
          model: 'unknown',
          source: 'unknown',
          message: 'Health check endpoint not reachable' 
        };
      });
  }

  // --- Document / RAG API ---

  /**
   * Upload extracted PDF text to the backend for vectorization and storage.
   * Returns the documentId assigned by the server.
   */
  async ingestDocument(filename: string, text: string): Promise<string> {
    const response = await fetch('/api/documents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filename, text })
    });
    if (!response.ok) {
      throw new Error(`Document ingestion failed: ${response.status}`);
    }
    const result = await response.json();
    if (!result.success) {
      throw new Error(result.error || 'Unknown error during ingestion');
    }
    return result.documentId;
  }

  /**
   * Check whether the RAG pipeline (embedding + Postgres) is available.
   */
  async checkRagStatus(): Promise<{ ragEnabled: boolean; hasDocuments: boolean }> {
    try {
      const response = await fetch('/api/documents/status');
      if (!response.ok) {
        return { ragEnabled: false, hasDocuments: false };
      }
      return await response.json();
    } catch {
      return { ragEnabled: false, hasDocuments: false };
    }
  }

  /**
   * Get Goose configuration including skills and MCP servers.
   */
  getConfig(): Promise<GooseConfig> {
    return fetch('/api/config')
      .then(response => response.json())
      .catch(error => {
        console.error('Config fetch failed:', error);
        return {
          skills: [],
          mcpServers: [],
          error: 'Configuration endpoint not reachable'
        };
      });
  }
}

