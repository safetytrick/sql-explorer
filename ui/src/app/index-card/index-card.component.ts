import {Component, EventEmitter, Input, OnChanges, Output} from '@angular/core';
import {IndexUsage} from '../schema-lens.service';
import {orderIndexesByColumns} from './index-card';

/**
 * A table's indexes as a card: kind, name, columns and the index hints that
 * name each one, including hinted indexes the table does not have. Shared by
 * the table page, where a hinted index drills into its queries, and the
 * change page, where an index is picked for the builder.
 */
@Component({
  selector: 'app-index-card',
  templateUrl: './index-card.component.html',
  styleUrls: ['./index-card.component.scss']
})
export class IndexCardComponent implements OnChanges {
  @Input() indexes: IndexUsage[] = [];
  /** The highlighted index's name. */
  @Input() selected: string | null = null;
  /** Which rows can be clicked: those with hinting queries, or every index the table has. */
  @Input() clickable: 'hinted' | 'existing' = 'hinted';
  /** Hover text on a clickable row. */
  @Input() clickTitle = '';
  @Input() note = '';
  @Output() picked = new EventEmitter<IndexUsage>();

  ordered: IndexUsage[] = [];

  ngOnChanges() {
    this.ordered = orderIndexesByColumns(this.indexes ?? []);
  }

  isClickable(index: IndexUsage): boolean {
    return this.clickable === 'hinted' ? index.queries > 0 : index.exists;
  }

  pick(index: IndexUsage) {
    if (this.isClickable(index)) { this.picked.emit(index); }
  }
}
