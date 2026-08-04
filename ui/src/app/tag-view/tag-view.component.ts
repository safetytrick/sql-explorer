import {Component, Input, OnDestroy, OnInit} from '@angular/core';
import {Subject, Subscription} from 'rxjs';
import {debounceTime, switchMap} from 'rxjs/operators';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ChipStyle, TagColorsService} from '../tag-colors.service';
import {Tag} from '../api-types';

/**
 * Tag chip editor: colored chips (tag colors via TagColorsService) with a
 * plain-text add input and debounced autocomplete suggestions. Hand-rolled
 * in the sidebar's chip vocabulary — no Material.
 */
@Component({
    selector: 'app-tag-view',
    templateUrl: './tag-view.component.html',
    styleUrls: ['./tag-view.component.scss'],
    imports: []
})
export class TagViewComponent implements OnInit, OnDestroy {

  @Input()
  path!: string;

  @Input()
  tags: Tag[] | null = null;

  tagInput = '';
  suggestions: string[] = [];

  private readonly input$ = new Subject<string>();
  private subscriptions = new Subscription();

  constructor(private sqlService: AnalyzedSqlService,
              private tagColorsService: TagColorsService) {}

  ngOnInit() {
    if (this.tags === null) {
      this.sqlService.getTags(this.path).subscribe(data => this.tags = data);
    }
    this.subscriptions.add(this.input$.pipe(
      debounceTime(200),
      switchMap(value => this.sqlService.autoCompleteTags(value))
    ).subscribe(names => this.suggestions = names.filter(name => !this.hasTag(name))));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  onInput(value: string) {
    this.tagInput = value;
    if (value.trim()) {
      this.input$.next(value.trim());
    } else {
      this.suggestions = [];
    }
  }

  addTag(name: string) {
    const value = name.trim();
    if (value && !this.hasTag(value)) {
      this.sqlService.addTag(value, this.path).subscribe(tag => {
        this.tags = [...(this.tags ?? []), tag];
      });
    }
    this.tagInput = '';
    this.suggestions = [];
  }

  removeTag(tag: Tag) {
    this.sqlService.removeTag(tag.id).subscribe();
    this.tags = this.tags!.filter(t => t.id !== tag.id);
  }

  chipStyle(name: string): ChipStyle {
    return this.tagColorsService.chipStyle(name);
  }

  private hasTag(name: string): boolean {
    return (this.tags ?? []).some(tag => tag.name.toLowerCase() === name.toLowerCase());
  }
}
