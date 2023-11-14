import { Component } from '@angular/core';
import {AnalyzedSqlService} from './analyzed-sql.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css'],
  providers: [AnalyzedSqlService]
})
export class AppComponent {
  title = 'app';
  showExplain = false;

}
