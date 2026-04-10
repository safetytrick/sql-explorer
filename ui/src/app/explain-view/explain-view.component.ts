import {Component, OnInit, ViewChild} from '@angular/core';
import {MatLegacyTable as MatTable, MatLegacyTableDataSource as MatTableDataSource} from '@angular/material/legacy-table';
import {TSV} from 'tsv';

@Component({
  selector: 'app-explain-view',
  templateUrl: './explain-view.component.html',
  styleUrls: ['./explain-view.component.scss']
})
export class ExplainViewComponent implements OnInit {

  dataSource = new MatTableDataSource<any>();
  displayedColumns = ['id', 'select_type', 'table',
    'type', 'possible_keys', 'key', 'key_len', 'ref', 'rows', 'filtered', 'Extra'];

  public text = '';

  @ViewChild(MatTable, {static: true}) table: MatTable<any>;


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

  private parseAndExtract(text) {
    return TSV.parse(text)
      .filter(item => item.hasOwnProperty('id') && item.id !== undefined && item.id !== '');
  }

}
