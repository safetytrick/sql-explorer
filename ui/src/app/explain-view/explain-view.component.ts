import {Component, OnInit, ViewChild} from '@angular/core';
import { MatTable, MatTableDataSource, MatColumnDef, MatHeaderCellDef, MatHeaderCell, MatCellDef, MatCell, MatHeaderRowDef, MatHeaderRow, MatRowDef, MatRow } from '@angular/material/table';
import {TSV} from 'tsv';
import { FormsModule } from '@angular/forms';

@Component({
    selector: 'app-explain-view',
    templateUrl: './explain-view.component.html',
    styleUrls: ['./explain-view.component.scss'],
    imports: [FormsModule, MatTable, MatColumnDef, MatHeaderCellDef, MatHeaderCell, MatCellDef, MatCell, MatHeaderRowDef, MatHeaderRow, MatRowDef, MatRow]
})
export class ExplainViewComponent implements OnInit {

  dataSource = new MatTableDataSource<any>();
  displayedColumns = ['id', 'select_type', 'table',
    'type', 'possible_keys', 'key', 'key_len', 'ref', 'rows', 'filtered', 'Extra'];

  public text = '';

  @ViewChild(MatTable, {static: true}) table!: MatTable<any>;


  constructor() { }

  ngOnInit() {
    this.textValue = this.text;
  }

  get textValue() {
    return this.text;
  }

  set textValue(textValue) {
    this.dataSource = this.parseAndExtract(textValue);
    this.text = textValue;
  }

  private parseAndExtract(text: string) {
    return TSV.parse(text)
      .filter((item: any) => item.hasOwnProperty('id') && item.id !== undefined && item.id !== '');
  }

}
