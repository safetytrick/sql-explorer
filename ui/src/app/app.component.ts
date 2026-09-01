import { Component, OnInit, inject } from '@angular/core';
import {AnalyzedSqlService} from './analyzed-sql.service';
import {ExplainService} from './explain.service';
import { NavBarComponent } from './nav-bar/nav-bar.component';
import { ExplainViewComponent } from './explain-view/explain-view.component';
import { RouterOutlet } from '@angular/router';
import { SchemaTreeComponent } from './schema-tree/schema-tree.component';
import { EnvironmentUrlSync } from './environment-url-sync';

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

  constructor(private explainService: ExplainService) { }

  ngOnInit() {
    // Started here rather than in the service's constructor: the service is
    // injected by the HTTP interceptor, which runs before the router is ready.
    this.environmentUrl.start();
  }

  get showExplain(): boolean {
    return this.explainService.showExplain;
  }
}
