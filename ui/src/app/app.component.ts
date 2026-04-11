import { Component } from '@angular/core';
import {AnalyzedSqlService} from './analyzed-sql.service';
import { NavBarComponent } from './nav-bar/nav-bar.component';
import { ExplainViewComponent } from './explain-view/explain-view.component';
import { RouterOutlet } from '@angular/router';

@Component({
    selector: 'app-root',
    templateUrl: './app.component.html',
    styleUrls: ['./app.component.css'],
    providers: [AnalyzedSqlService],
    imports: [NavBarComponent, ExplainViewComponent, RouterOutlet]
})
export class AppComponent {
  title = 'app';
  showExplain = false;

}
