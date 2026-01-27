import { Component, OnInit, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { ChatComponent } from './components/chat/chat.component';
import { AuthService } from './services/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, MatToolbarModule, MatIconModule, MatButtonModule, MatMenuModule, ChatComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit {
  protected readonly title = signal('Goose Agent Chat');
  protected readonly displayName = signal<string | null>(null);
  protected readonly isAuthenticated = signal(false);

  constructor(private authService: AuthService) {}

  ngOnInit(): void {
    this.authService.checkAuthStatus().then(status => {
      this.isAuthenticated.set(status.authenticated);
      if (status.authenticated && status.displayName) {
        this.displayName.set(status.displayName);
      }
    });
  }

  logout(): void {
    this.authService.logout();
  }
}
