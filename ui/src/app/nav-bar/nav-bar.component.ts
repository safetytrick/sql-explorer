import { Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import {environmentState, environmentStatusLine, isProblem, STATE_LABEL, STATE_MEANING} from '../environment-health';
import {EnvironmentState} from '../environment.service';
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
export class NavBarComponent implements OnInit, OnDestroy {

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
    this.refreshEnvironments();
    this.environmentService.unknown$.subscribe(name => this.unknownEnvironment = name);
  }

  ngOnDestroy() {
    clearTimeout(this.refreshTimer);
  }

  /**
   * Read the environments' states, and read them again while any is pulling:
   * a restart's pull runs in the background, and the status has to change
   * when it lands rather than when someone next reloads (sqlx-nbi.8).
   */
  private refreshTimer?: ReturnType<typeof setTimeout>;

  private refreshEnvironments() {
    clearTimeout(this.refreshTimer);
    this.environmentService.getEnvironments().subscribe(list => {
      this.environments = list.environments;
      if (!this.current) {
        this.current = list.defaultEnvironment;
      }
      if (list.environments.some(env => environmentState(env) === 'PULLING')) {
        this.refreshTimer = setTimeout(() => this.refreshEnvironments(), 5000);
      }
    });
  }

  /** Environments with a pull this page started, and why one failed. */
  pullingNow = new Set<string>();
  pullError: Record<string, string> = {};

  /** Pull now: read the environment's servers, which takes a cached one live (sqlx-nbi.5). */
  pull(name: string, event: Event) {
    event.stopPropagation();
    this.pullingNow.add(name);
    delete this.pullError[name];
    this.environmentService.pull(name).subscribe({
      next: () => { this.pullingNow.delete(name); this.refreshEnvironments(); },
      error: err => {
        this.pullingNow.delete(name);
        this.pullError[name] = err?.error?.message || err?.message || 'the pull failed';
        this.refreshEnvironments();
      },
    });
  }

  state(env: EnvironmentSummary): EnvironmentState {
    return this.pullingNow.has(env.name) ? 'PULLING' : environmentState(env);
  }

  problem(env: EnvironmentSummary): boolean {
    return isProblem(this.state(env));
  }

  readonly stateLabel = STATE_LABEL;

  /** Hover text: what the state means, then each problem. */
  stateTitle(env: EnvironmentSummary): string {
    const problems = env.health?.problems ?? [];
    return [STATE_MEANING[this.state(env)], ...problems.map(p => '• ' + p)].join('\n');
  }

  /** Hover text on the server count: each server with its roles, then where sampling goes (sqlx-n5j.4). */
  serversTitle(env: EnvironmentSummary): string {
    const lines = env.servers.map(server =>
      server.name + (server.roles?.length ? ' — ' + server.roles.join(', ') : ''));
    if (env.sampling) {
      lines.push(env.sampling.available
        ? 'sampling: ' + env.sampling.server
        : env.sampling.server ? 'sampling: ' + env.sampling.server + ' (not now: cached)' : 'sampling: none');
    }
    return lines.join('\n');
  }

  /** The environment chooser is open (sqlx-m6r.6). */
  envOpen = false;

  toggleEnv(event: Event) {
    event.stopPropagation();
    this.envOpen = !this.envOpen;
    this.adminOpen = false;
    if (this.envOpen) {
      // statuses change as sources load; read them fresh when the chooser opens
      this.refreshEnvironments();
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

  /** "cached · 6h old · 216k statements", or what is wrong. */
  statusLine(env: EnvironmentSummary): string {
    return this.pullingNow.has(env.name) ? 'pulling…' : environmentStatusLine(env);
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
