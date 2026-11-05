import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute, Router} from '@angular/router';
import { SmallSqlViewComponent } from '../small-sql-view/small-sql-view.component';
import { SqlProfile } from '../api-types';

@Component({
    selector: 'app-tagged-sql-view',
    templateUrl: './tagged-sql-view.component.html',
    styleUrls: ['./tagged-sql-view.component.scss'],
    imports: [SmallSqlViewComponent]
})
export class TaggedSqlViewComponent implements OnInit {
  public taggedQueries!: SqlProfile[];
  public tag!: string;

  constructor(public sqlService: AnalyzedSqlService,
              private route: ActivatedRoute,
              private router: Router) { }

  ngOnInit() {
    this.route.params.subscribe(params => {
      this.tag = params['tag'];
      this.getSqlByTag(this.tag);
    });

  }

  private getSqlByTag(tag: string) {
    this.sqlService.getSqlByTag(tag).subscribe(
      data => {
        this.taggedQueries = data;
      }
    );
  }
}
