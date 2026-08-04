import {Component, Input} from '@angular/core';
import {RouterLink} from '@angular/router';
import {CallbackPipePipe} from '../callback-pipe.pipe';
import {TableStructure} from '../api-types';

@Component({
    selector: 'app-small-table-view',
    templateUrl: './small-table-view.component.html',
    styleUrls: ['./small-table-view.component.scss'],
    imports: [RouterLink, CallbackPipePipe]
})
export class SmallTableViewComponent {

  /**
   * Null-tolerant: a query can reference tables the loaded schema doesn't
   * know (dropped tables, other servers, or misextracted references), and
   * the metadata endpoint returns an empty body for those.
   */
  @Input()
  table: TableStructure | null = null;
}
