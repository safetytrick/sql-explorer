import { Component, OnInit } from '@angular/core';
import {SourceService} from '../source.service';
import {MatSelectionList} from '@angular/material/list';

@Component({
    selector: 'app-admin-source-list',
    templateUrl: './admin-source-list.component.html',
    styleUrls: ['./admin-source-list.component.scss'],
    standalone: false
})
export class AdminSourceListComponent implements OnInit {

  sources: Array<any>;
  active: Array<any>;
  all: Array<any>;

  constructor(private sourceService: SourceService) { }

  ngOnInit() {
    const activePromise = this.sourceService.getActiveSources()
      .subscribe(active => this.active = active);
    const allPromise = this.sourceService.getAllSources().subscribe(
      all => this.all = all);
  }

  isActive(source) {
    return this.active.find((value => value.source.sourceRef === source.sourceRef));
  }

  applyChange(sourcesList: MatSelectionList) {
    const selected = sourcesList.selectedOptions.selected.map(value => value.value);
    this.sourceService.loadSources(selected.map(value => {
      return { 'sourceRef': value };
    })).subscribe(complete => {
      console.log('Loaded: ' + selected.join(', '));
    });
  }

  cancelChange(sourcesList: MatSelectionList) {
    sourcesList.options.forEach(option => {
      option.selected = this.isActive({'sourceRef': option.value});
    });
    console.log('Options Reset');
  }
}
