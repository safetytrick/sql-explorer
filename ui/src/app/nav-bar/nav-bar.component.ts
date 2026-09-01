import { Component, HostListener, OnInit } from '@angular/core';
import {ExplainService} from '../explain.service';
import { Router, RouterLink } from '@angular/router';
import { EnvironmentService, EnvironmentSummary } from '../environment.service';
import { withEnv } from '../environment-url';
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

  /**
   * An environment a link asked for that does not exist.
   *
   * <p>Shown rather than silently corrected: the backend falls back to its
   * default for an unknown name, so without this the recipient of a stale link
   * would be reading another corpus's numbers with nothing to say so.
   */
  unknownEnvironment: string | null = null;
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
    this.environmentService.unknown$.subscribe(name => this.unknownEnvironment = name);
  }

  onEnvChange(name: string) {
    this.environmentService.setCurrent(name);
    this.current = name;
    // Stay on the current route, but re-instantiate it so its data reloads for
    // the newly selected environment. The interceptor scopes every request to
    // EnvironmentService.getCurrent(), so re-fetching pulls the new env's data.
    //
    // The address gains the new environment in the same navigation rather than
    // being stamped afterwards: the URL to return to is captured before any
    // later stamp could land, so stamping second would navigate back to the old
    // environment's address and only then correct it.
    const target = withEnv(this.router.url, name);
    this.router.navigateByUrl('/', { skipLocationChange: true })
      .then(() => this.router.navigateByUrl(target));
  }

  onThemeChange(id: string) {
    this.themeService.setCurrent(id);
    this.currentTheme = id;
  }

  toggleExplain() {
    this.explainService.toggle();
  }

}
