import { Component, OnInit } from '@angular/core';
import {FormControl} from '@angular/forms';
import {AnalyzedSqlService} from '../analyzed-sql.service';

@Component({
  selector: 'app-query-utilities',
  templateUrl: './query-utilities.component.html',
  styleUrls: ['./query-utilities.component.scss']
})
export class QueryUtilitiesComponent implements OnInit {
  result: string;
  inputControl = new FormControl('');

  constructor(public sqlService: AnalyzedSqlService) { }

  ngOnInit() {
  }

  doNormalize() {
    if (this.inputControl.value) {
      this.sqlService.normalizeQuery(this.inputControl.value).subscribe(
        (response: any) => {
          this.result = response.text;
        }
      );
    }
  }

  doClean() {
    if (this.inputControl.value) {
      this.sqlService.cleanQuery(this.inputControl.value).subscribe(
        (response: any) => {
          this.result = response.sql;
        }
      );
    }
  }
}
