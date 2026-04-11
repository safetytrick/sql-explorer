import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute, Router} from '@angular/router';
import {Title} from '@angular/platform-browser';

@Component({
    selector: 'app-tagged-table-view',
    templateUrl: './tagged-table-view.component.html',
    styleUrls: ['./tagged-table-view.component.scss'],
    standalone: false
})
export class TaggedTableViewComponent implements OnInit {
  tag: string;
  taggedTables: [];


  constructor(public sqlService: AnalyzedSqlService,
              private route: ActivatedRoute,
              private router: Router,
              private titleService: Title) { }

  ngOnInit() {
    this.route.params.subscribe(params => {
          this.applyTag(params['tag']);
        });
  }

  private applyTag(tag: string) {
    this.tag = tag;
    this.getTablesByTag(this.tag);
    this.titleService.setTitle('tag: ' + this.tag);
  }

  private getTablesByTag(tag: string) {
    this.sqlService.getTablesByTag(tag).subscribe(
      (data: []) => {
        this.taggedTables = data;
      }
    );
  }
}
