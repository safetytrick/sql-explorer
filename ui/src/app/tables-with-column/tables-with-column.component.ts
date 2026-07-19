import {Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Title} from '@angular/platform-browser';
import { TableListComponent } from '../table-list-component/table-list.component';
import { TableRollup } from '../api-types';

@Component({
    selector: 'app-tables-with-column',
    templateUrl: './tables-with-column.component.html',
    styleUrls: ['./tables-with-column.component.scss'],
    imports: [TableListComponent]
})
export class TablesWithColumnComponent implements OnInit {

    column!: string;
    tables!: TableRollup[];


    constructor(public sqlService: AnalyzedSqlService,
                private route: ActivatedRoute,
                private titleService: Title) { }

    ngOnInit() {
        this.column = this.route.snapshot.params['column'];
        this.getTablesWithColumn(this.column);
        this.titleService.setTitle('tables with: ' + this.column);
    }

    private getTablesWithColumn(tag: string) {
        this.sqlService.getTablesWithColumn(tag).subscribe(
            data => {
                this.tables = data;
            }
        );
    }

}
