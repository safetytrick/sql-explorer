import {Component} from '@angular/core';
import {ThemeOption, THEMES, ThemeService} from '../theme.service';

/**
 * Per-viewer preferences (sqlx-m6r.5). The theme picker lived in the nav, where
 * it sat beside the environment and competed with it for attention every
 * time either was glanced at; a theme is chosen once, so it lives here.
 * Stored in this browser only, as before.
 */
@Component({
  selector: 'app-settings',
  templateUrl: './settings.component.html',
  styleUrls: ['./settings.component.scss']
})
export class SettingsComponent {
  readonly themes: ThemeOption[] = THEMES;

  constructor(private themeService: ThemeService) {}

  get current(): string {
    return this.themeService.getCurrent();
  }

  choose(id: string) {
    this.themeService.setCurrent(id);
  }
}
