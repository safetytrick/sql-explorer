import { Component, OnInit, inject } from '@angular/core';
import {AnalyzedSqlService} from './analyzed-sql.service';
import {ExplainService} from './explain.service';
import { NavBarComponent } from './nav-bar/nav-bar.component';
import { ExplainViewComponent } from './explain-view/explain-view.component';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { SchemaTreeComponent } from './schema-tree/schema-tree.component';
import { EnvironmentUrlSync } from './environment-url-sync';
import { QueryScopeService } from './query-scope.service';

@Component({
    selector: 'app-root',
    templateUrl: './app.component.html',
    styleUrls: ['./app.component.css'],
    providers: [AnalyzedSqlService],
    imports: [NavBarComponent, ExplainViewComponent, RouterOutlet, SchemaTreeComponent]
})
export class AppComponent implements OnInit {
  title = 'app';

  private readonly environmentUrl = inject(EnvironmentUrlSync);

  /**
   * The schema tree is the workbench's scope picker and nothing else's
   * (sqlx-pyz.1). On every other page it only navigated away, and loading it
   * delayed the page it sat beside, so it renders on /queries alone.
   */
  showTree = false;
  /** Folded away by the workbench, which owns it as `var-hide=tree`. */
  treeHidden = false;
  private readonly router = inject(Router);
  private readonly queryScope = inject(QueryScopeService);

  constructor(private explainService: ExplainService) { }

  static isTreePage(url: string): boolean {
    return url.split(/[?#]/)[0] === '/queries';
  }

  ngOnInit() {
    // Started here rather than in the service's constructor: the service is
    // injected by the HTTP interceptor, which runs before the router is ready.
    this.environmentUrl.start();
    this.showTree = AppComponent.isTreePage(this.router.url);
    this.queryScope.treeHidden$.subscribe(hidden => this.treeHidden = hidden);
    this.router.events.pipe(filter(event => event instanceof NavigationEnd))
      .subscribe(event => this.showTree = AppComponent.isTreePage((event as NavigationEnd).urlAfterRedirects));
  }

  get showExplain(): boolean {
    return this.explainService.showExplain;
  }
}
