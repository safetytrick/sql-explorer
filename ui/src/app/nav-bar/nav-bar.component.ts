import { Component, HostListener, OnInit } from '@angular/core';
import {ExplainService} from '../explain.service';
import { Router, RouterLink } from '@angular/router';
import { EnvironmentService, EnvironmentSummary } from '../environment.service';
import { ThemeService, THEMES, ThemeOption } from '../theme.service';

@Component({
    selector: 'app-nav-bar',
    templateUrl: './nav-bar.component.html',
    styleUrls: ['./nav-bar.component.scss'],
    imports: [RouterLink]
})
export class NavBarComponent implements OnInit {

  /** Admin menu open state — replaces Bootstrap's dropdown JS. */
  adminOpen = false;

  environments: EnvironmentSummary[] = [];
  current: string | null = null;
  themes: ThemeOption[] = THEMES;
  currentTheme = '';

  constructor(private explainService: ExplainService,
              private environmentService: EnvironmentService,
              private themeService: ThemeService,
              private router: Router) { }

  toggleAdmin(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    this.adminOpen = !this.adminOpen;
  }

  closeAdmin() {
    this.adminOpen = false;
  }

  /** Any click outside the menu dismisses it, as the Bootstrap version did. */
  @HostListener('document:click')
  onDocumentClick() {
    this.adminOpen = false;
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.adminOpen = false;
  }

  ngOnInit() {
    this.currentTheme = this.themeService.getCurrent();
    this.current = this.environmentService.getCurrent();
    this.environmentService.getEnvironments().subscribe(list => {
      this.environments = list.environments;
      if (!this.current) {
        this.current = list.defaultEnvironment;
      }
    });
  }

  onEnvChange(name: string) {
    this.environmentService.setCurrent(name);
    this.current = name;
    // Stay on the current route, but re-instantiate it so its data reloads for
    // the newly selected environment. The interceptor scopes every request to
    // EnvironmentService.getCurrent(), so re-fetching pulls the new env's data.
    const url = this.router.url;
    this.router.navigateByUrl('/', { skipLocationChange: true })
      .then(() => this.router.navigateByUrl(url));
  }

  onThemeChange(id: string) {
    this.themeService.setCurrent(id);
    this.currentTheme = id;
  }

  toggleExplain() {
    this.explainService.toggle();
  }

}
