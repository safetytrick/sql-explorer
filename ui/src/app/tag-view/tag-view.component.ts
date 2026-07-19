import {Component, Input, OnInit} from '@angular/core';
import {Observable} from 'rxjs';
import {debounceTime, switchMap} from 'rxjs/operators';
import {FormsModule, ReactiveFormsModule, UntypedFormControl} from '@angular/forms';
import {MatChipGrid, MatChipInput, MatChipInputEvent, MatChipRemove, MatChipRow} from '@angular/material/chips';
import {
  MatAutocomplete,
  MatAutocompleteSelectedEvent,
  MatAutocompleteTrigger,
  MatOption
} from '@angular/material/autocomplete';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {MatIcon} from '@angular/material/icon';
import {AsyncPipe} from '@angular/common';
import {Tag} from '../api-types';

@Component({
    selector: 'app-tag-view',
    templateUrl: './tag-view.component.html',
    styleUrls: ['./tag-view.component.scss'],
    imports: [MatChipGrid, MatChipRow, MatChipRemove, MatIcon, MatChipInput, FormsModule, MatAutocompleteTrigger, ReactiveFormsModule, MatAutocomplete, MatOption, AsyncPipe]
})
export class TagViewComponent implements OnInit {

  @Input()
  path!: string;

  @Input()
  tags: Tag[] | null = null;

  tagInputControl = new UntypedFormControl('');
  filteredTags!: Observable<string[]>;

  constructor(public sqlService: AnalyzedSqlService) {}

  ngOnInit() {
    if (this.tags === null) {
      this.sqlService.getTags(this.path).subscribe(data => this.tags = data);
    }
    this.filteredTags = this.tagInputControl.valueChanges.pipe(
      debounceTime(200),
      switchMap((value: string) => this.sqlService.autoCompleteTags(value || ''))
    ) as Observable<string[]>;
  }

  addTag(event: MatChipInputEvent) {
    const value = (event.value || '').trim();
    if (value) {
      this.sqlService.addTag(value, this.path).subscribe(tag => {
        if (!this.tags) { this.tags = []; }
        this.tags = [...this.tags, tag];
      });
    }
    event.chipInput!.clear();
    this.tagInputControl.setValue(null);
  }

  selectTag(event: MatAutocompleteSelectedEvent) {
    const tagName: string = event.option.value;
    this.sqlService.addTag(tagName, this.path).subscribe(tag => {
      if (!this.tags) { this.tags = []; }
      this.tags = [...this.tags, tag];
    });
    this.tagInputControl.setValue(null);
  }

  removeTag(tag: Tag) {
    this.sqlService.removeTag(tag.id).subscribe();
    this.tags = this.tags!.filter(t => t.id !== tag.id);
  }
}
