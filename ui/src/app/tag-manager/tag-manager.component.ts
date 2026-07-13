import { Component, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TagColorsService, TagSummary } from '../tag-colors.service';

const TAG_COLORS = ['#f5a623', '#2dd4bf', '#a78bfa', '#4a9eff', '#f56565', '#3dd68c', '#8b93a8'];

@Component({
  selector: 'app-tag-manager',
  templateUrl: './tag-manager.component.html',
  styleUrls: ['./tag-manager.component.scss'],
  imports: [FormsModule, DatePipe, RouterLink]
})
export class TagManagerComponent implements OnInit {

  readonly colors = TAG_COLORS;

  tags: TagSummary[] = [];
  newTagName = '';
  selectedColor = TAG_COLORS[0];
  confirmingDelete: string | null = null;

  constructor(private tagColorsService: TagColorsService) { }

  ngOnInit() {
    this.reload();
  }

  private reload() {
    this.tagColorsService.getSummaries(true).subscribe(tags => this.tags = tags);
  }

  chip(name: string, color: string | null) {
    const value = color || '#8b93a8';
    return { color: value, background: value + '26' };
  }

  createTag() {
    const name = this.newTagName.trim();
    if (!name) { return; }
    this.tagColorsService.define(name, this.selectedColor).subscribe(() => {
      this.newTagName = '';
      this.reload();
    });
  }

  /** Row click loads the tag into the editor so its color can be changed. */
  edit(tag: TagSummary) {
    this.newTagName = tag.name;
    if (tag.color) {
      this.selectedColor = tag.color;
    }
  }

  deleteTag(name: string) {
    if (this.confirmingDelete !== name) {
      this.confirmingDelete = name;
      return;
    }
    this.confirmingDelete = null;
    this.tagColorsService.deleteTag(name).subscribe(() => this.reload());
  }
}
