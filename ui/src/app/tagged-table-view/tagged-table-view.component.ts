import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TableListComponent } from '../table-list-component/table-list.component';
import { TableRollup } from '../api-types';

@Component({
    selector: 'app-tagged-table-view',
    templateUrl: './tagged-table-view.component.html',
    styleUrls: ['./tagged-table-view.component.scss'],
    imports: [RouterLink, TableListComponent]
})
export class TaggedTableViewComponent implements OnInit {
  tag!: string;
  taggedTables!: TableRollup[];


  constructor(public sqlService: AnalyzedSqlService,
              private route: ActivatedRoute,
              private router: Router) { }

  ngOnInit() {
    this.route.params.subscribe(params => {
          this.applyTag(params['tag']);
        });
  }

  private applyTag(tag: string) {
    this.tag = tag;
    this.getTablesByTag(this.tag);
  }

  private getTablesByTag(tag: string) {
    this.sqlService.getTablesByTag(tag).subscribe(
      data => {
        this.taggedTables = data;
      }
    );
  }
}
