import {Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from "../analyzed-sql.service";
import {Title} from "@angular/platform-browser";
import { SchemaListTableComponent } from '../schema-list-table/schema-list-table.component';
import { SchemaRollup } from '../api-types';

@Component({
    selector: 'app-schema-list',
    templateUrl: './schema-list.component.html',
    styleUrls: ['./schema-list.component.scss'],
    imports: [SchemaListTableComponent]
})
export class SchemaListComponent implements OnInit {

  schemas: SchemaRollup[] = [];
  constructor(public sqlService: AnalyzedSqlService,
              private titleService: Title) {
    titleService.setTitle('::: schemas :::');
  }

  ngOnInit() {
    this.schemas = [];
    this.sqlService.getSchemas().subscribe(data => {
      this.schemas = data;
    });
  }

}
