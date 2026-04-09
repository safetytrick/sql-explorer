import {Component, Input, OnInit} from '@angular/core';
import {Observable} from 'rxjs';
import {debounceTime, switchMap} from 'rxjs/operators';
import {UntypedFormControl} from '@angular/forms';
import {MatChipInputEvent} from '@angular/material/chips';
import {MatAutocompleteSelectedEvent} from '@angular/material/autocomplete';
import {AnalyzedSqlService} from '../analyzed-sql.service';

@Component({
  selector: 'app-tag-view',
  templateUrl: './tag-view.component.html',
  styleUrls: ['./tag-view.component.scss']
})
export class TagViewComponent implements OnInit {

  @Input()
  path: string;

  @Input()
  tags: any[] = null;

  tagInputControl = new UntypedFormControl('');
  filteredTags: Observable<any[]>;

  constructor(public sqlService: AnalyzedSqlService) {}

  ngOnInit() {
    if (this.tags === null) {
      this.sqlService.getTags(this.path).subscribe(data => this.tags = data);
    }
    this.filteredTags = this.tagInputControl.valueChanges.pipe(
      debounceTime(200),
      switchMap((value: string) => this.sqlService.autoCompleteTags(value || ''))
    ) as Observable<any[]>;
  }

  addTag(event: MatChipInputEvent) {
    event.input.value = '';
    this.tagInputControl.setValue(null);
  }

  selectTag(event: MatAutocompleteSelectedEvent) {
    const tag = event.option.value;
    this.sqlService.addTag(tag.name, this.path).subscribe();
    if (!this.tags) { this.tags = []; }
    this.tags = [...this.tags, tag];
    this.tagInputControl.setValue(null);
  }

  removeTag(tag: any) {
    this.sqlService.removeTag(tag.id).subscribe();
    this.tags = this.tags.filter(t => t.id !== tag.id);
  }
}
