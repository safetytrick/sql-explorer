import {Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from "../analyzed-sql.service";
import {Title} from "@angular/platform-browser";
import { RouterLink } from '@angular/router';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { SchemaRollup } from '../api-types';

@Component({
    selector: 'app-schema-list',
    templateUrl: './schema-list.component.html',
    styleUrls: ['./schema-list.component.scss'],
    imports: [RouterLink, PicoViewComponent]
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
