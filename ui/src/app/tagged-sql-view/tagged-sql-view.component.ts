import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute, Router} from '@angular/router';
import {Title} from '@angular/platform-browser';
import { SmallSqlViewComponent } from '../small-sql-view/small-sql-view.component';

@Component({
    selector: 'app-tagged-sql-view',
    templateUrl: './tagged-sql-view.component.html',
    styleUrls: ['./tagged-sql-view.component.scss'],
    imports: [SmallSqlViewComponent]
})
export class TaggedSqlViewComponent implements OnInit {
  public taggedQueries!: any[];
  public tag!: string;

  constructor(public sqlService: AnalyzedSqlService,
              private route: ActivatedRoute,
              private router: Router,
              private titleService: Title) { }

  ngOnInit() {
    this.route.params.subscribe(params => {
      this.tag = params['tag'];
      this.getSqlByTag(this.tag);
      this.titleService.setTitle('tag: ' + this.tag);
    });

  }

  private getSqlByTag(tag: string) {
    this.sqlService.getSqlByTag(tag).subscribe(
      (data: any) => {
        this.taggedQueries = data;
      }
    );
  }
}
