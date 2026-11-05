import {compactCount} from '../compact-count';
import { Component, HostListener, OnInit } from '@angular/core';
import {ExplainService} from '../explain.service';
import { RouterLink } from '@angular/router';
import { EnvironmentService, EnvironmentSummary } from '../environment.service';
import { EnvironmentUrlSync } from '../environment-url-sync';

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

  constructor(private explainService: ExplainService,
              private environmentService: EnvironmentService,
              private environmentUrl: EnvironmentUrlSync) { }

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
    this.envOpen = false;
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.adminOpen = false;
    this.envOpen = false;
  }

  ngOnInit() {
    this.current = this.environmentService.getCurrent();
    this.environmentService.getEnvironments().subscribe(list => {
      this.environments = list.environments;
      if (!this.current) {
        this.current = list.defaultEnvironment;
      }
    });
    this.environmentService.unknown$.subscribe(name => this.unknownEnvironment = name);
  }

  /** The environment chooser is open (sqlx-m6r.6). */
  envOpen = false;

  toggleEnv(event: Event) {
    event.stopPropagation();
    this.envOpen = !this.envOpen;
    this.adminOpen = false;
    if (this.envOpen) {
      // statuses change as sources load; read them fresh when the chooser opens
      this.environmentService.getEnvironments().subscribe(list => this.environments = list.environments);
    }
  }

  chooseEnv(name: string) {
    this.envOpen = false;
    if (name !== this.current || this.unknownEnvironment) {
      this.onEnvChange(name);
    }
  }

  currentSummary(): EnvironmentSummary | undefined {
    return this.environments.find(env => env.name === this.current);
  }

  /** "216k statements · loaded Nov 4, 9:29 PM", or what is missing. */
  statusLine(env: EnvironmentSummary): string {
    const sources = env.sources;
    if (!sources?.loaded) { return 'nothing loaded'; }
    const count = sources.importStats?.count;
    const statements = count === null || count === undefined ? `${sources.sourceCount} source${sources.sourceCount === 1 ? '' : 's'}`
      : `${compactCount(count)} statements`;
    const when = sources.loadedOn ? ' · loaded ' + new Date(sources.loadedOn).toLocaleString('en-US',
      {month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'}) : '';
    return statements + when;
  }

  onEnvChange(name: string) {
    this.current = name;
    // Requests, address and data all move together; EnvironmentUrlSync owns
    // that because the reload and the address change have to be one sequence.
    this.environmentUrl.switchTo(name);
  }

  toggleExplain() {
    this.explainService.toggle();
  }

}
