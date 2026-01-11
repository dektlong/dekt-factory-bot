import { Component, computed, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivityEvent, TodoItem } from '../../services/chat.service';
import { TodoListComponent } from '../todo-list/todo-list.component';

@Component({
  selector: 'app-activity-panel',
  standalone: true,
  imports: [
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatCardModule,
    MatProgressSpinnerModule,
    TodoListComponent
  ],
  templateUrl: './activity-panel.component.html',
  styleUrl: './activity-panel.component.scss'
})
export class ActivityPanelComponent {
  activities = input<ActivityEvent[]>([]);
  todos = input<TodoItem[]>([]);
  collapsed = input<boolean>(false);
  collapseToggle = output<void>();
  
  hasTodos = computed(() => this.todos().length > 0);
  
  // Group activities by their status for visual organization
  runningActivities = computed(() => 
    this.activities().filter(a => a.status === 'running')
  );
  
  completedActivities = computed(() => 
    this.activities().filter(a => a.status === 'completed' || a.status === 'info')
  );
  
  hasActivities = computed(() => this.activities().length > 0);

  toggleCollapse(): void {
    this.collapseToggle.emit();
  }

  getActivityIcon(activity: ActivityEvent): string {
    if (activity.type === 'notification') {
      return 'info';
    }

    switch (activity.status) {
      case 'running': return 'pending';
      case 'error': return 'error';
      default: return 'check_circle';
    }
  }

  getActivityTitle(activity: ActivityEvent): string {
    if (activity.type === 'notification') {
      return activity.extensionId || 'Notification';
    }
    return activity.toolName || 'Tool';
  }

  getActivitySubtitle(activity: ActivityEvent): string {
    if (activity.extensionId && activity.type !== 'notification') {
      return activity.extensionId;
    }
    return '';
  }

  formatArguments(args: Record<string, unknown> | undefined): string[] {
    if (!args) return [];
    return Object.entries(args)
      .slice(0, 5) // Limit to first 5 arguments
      .map(([key, value]) => {
        const strValue = typeof value === 'string' 
          ? value 
          : JSON.stringify(value);
        // Truncate long values
        const truncated = strValue.length > 50 
          ? strValue.substring(0, 50) + '...' 
          : strValue;
        return `${key}: ${truncated}`;
      });
  }
}
