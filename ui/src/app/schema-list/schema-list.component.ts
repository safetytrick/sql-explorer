import {Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from "../analyzed-sql.service";
import {Title} from "@angular/platform-browser";

@Component({
  selector: 'app-schema-list',
  templateUrl: './schema-list.component.html',
  styleUrls: ['./schema-list.component.scss']
})
export class SchemaListComponent implements OnInit {

  schemas: any = [];
  constructor(public sqlService: AnalyzedSqlService,
              private titleService: Title) {
    titleService.setTitle('::: schemas :::');
  }

  ngOnInit() {
    this.schemas = [];
    this.sqlService.getSchemas().subscribe((data: {}) => {
      this.schemas = data;
    });
  }

}
