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
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.adminOpen = false;
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
