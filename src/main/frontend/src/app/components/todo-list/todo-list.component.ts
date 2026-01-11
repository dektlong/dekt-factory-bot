import { Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TodoItem } from '../../services/chat.service';

@Component({
  selector: 'app-todo-list',
  standalone: true,
  imports: [
    MatIconModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './todo-list.component.html',
  styleUrl: './todo-list.component.scss'
})
export class TodoListComponent {
  todos = input<TodoItem[]>([]);

  getStatusIcon(status: TodoItem['status']): string {
    switch (status) {
      case 'completed': return 'check_circle';
      case 'in_progress': return 'pending';
      case 'cancelled': return 'cancel';
      default: return 'radio_button_unchecked';
    }
  }
}
