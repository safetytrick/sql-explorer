import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import { ActivatedRoute } from '@angular/router';
import { SchemaListTableComponent } from '../schema-list-table/schema-list-table.component';
import { SchemaRollup } from '../api-types';

@Component({
    selector: 'app-tagged-schema-view',
    templateUrl: './tagged-schema-view.component.html',
    styleUrls: ['./tagged-schema-view.component.scss'],
    imports: [SchemaListTableComponent]
})
export class TaggedSchemaViewComponent implements OnInit {
  tag!: string;
  taggedSchemas!: SchemaRollup[];

  constructor(public sqlService: AnalyzedSqlService,
              private route: ActivatedRoute) { }

  ngOnInit() {
    this.route.params.subscribe(params => {
          this.applyTag(params['tag']);
        });
  }

  private applyTag(tag: string) {
    this.tag = tag;
    this.getSchemasByTag(this.tag);
  }

  private getSchemasByTag(tag: string) {
    this.sqlService.getSchemasByTag(tag).subscribe(
      data => {
        this.taggedSchemas = data;
      }
    );
  }
}
