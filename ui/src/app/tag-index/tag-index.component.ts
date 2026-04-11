import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {Title} from '@angular/platform-browser';
import { RouterLink } from '@angular/router';

@Component({
    selector: 'app-tag-index',
    templateUrl: './tag-index.component.html',
    styleUrls: ['./tag-index.component.scss'],
    imports: [RouterLink]
})
export class TagIndexComponent implements OnInit {

  public queryTags!: any[];
  public tableTags!: any[];

  constructor(public sqlService: AnalyzedSqlService,
              private titleService: Title) { }

  ngOnInit() {
    this.titleService.setTitle('::: tags :::');
    this.sqlService.getTagsByType('query').subscribe(
      (data: any) => {
        this.queryTags = data;
      }
    );

    this.sqlService.getTagsByType('table').subscribe(
      (data: any) => {
        this.tableTags = data;
      }
    );
  }

}
