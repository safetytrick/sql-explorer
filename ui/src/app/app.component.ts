import { Component } from '@angular/core';
import {AnalyzedSqlService} from './analyzed-sql.service';
import { NavBarComponent } from './nav-bar/nav-bar.component';
import { ExplainViewComponent } from './explain-view/explain-view.component';
import { RouterOutlet } from '@angular/router';
import { SchemaTreeComponent } from './schema-tree/schema-tree.component';

@Component({
    selector: 'app-root',
    templateUrl: './app.component.html',
    styleUrls: ['./app.component.css'],
    providers: [AnalyzedSqlService],
    imports: [NavBarComponent, ExplainViewComponent, RouterOutlet, SchemaTreeComponent]
})
export class AppComponent {
  title = 'app';
  showExplain = false;

}
