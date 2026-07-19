import { Component, OnInit } from '@angular/core';
import { UntypedFormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import { Highlight } from 'ngx-highlightjs';

@Component({
    selector: 'app-query-utilities',
    templateUrl: './query-utilities.component.html',
    styleUrls: ['./query-utilities.component.scss'],
    imports: [Highlight, FormsModule, ReactiveFormsModule]
})
export class QueryUtilitiesComponent implements OnInit {
  result!: string;
  inputControl = new UntypedFormControl('');

  constructor(public sqlService: AnalyzedSqlService) { }

  ngOnInit() {
  }

  doNormalize() {
    if (this.inputControl.value) {
      this.sqlService.normalizeQuery(this.inputControl.value).subscribe(
        response => {
          this.result = response.text;
        }
      );
    }
  }

  doClean() {
    if (this.inputControl.value) {
      this.sqlService.cleanQuery(this.inputControl.value).subscribe(
        response => {
          this.result = response.sql;
        }
      );
    }
  }
}
