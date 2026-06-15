import { Component, OnInit } from '@angular/core';
import {SourceService} from '../source.service';
import {EnvironmentService} from '../environment.service';
import { MatSelectionList, MatListOption } from '@angular/material/list';
import { DatePipe } from '@angular/common';

@Component({
    selector: 'app-admin-source-list',
    templateUrl: './admin-source-list.component.html',
    styleUrls: ['./admin-source-list.component.scss'],
    imports: [MatSelectionList, MatListOption, DatePipe]
})
export class AdminSourceListComponent implements OnInit {

  sources!: Array<any>;
  active!: Array<any>;
  all!: Array<any>;
  currentEnvironment: string | null;

  constructor(private sourceService: SourceService, environmentService: EnvironmentService) {
    this.currentEnvironment = environmentService.getCurrent();
  }

  ngOnInit() {
    const activePromise = this.sourceService.getActiveSources()
      .subscribe(active => this.active = active);
    const allPromise = this.sourceService.getAllSources().subscribe(
      all => this.all = all);
  }

  isActive(source: any) {
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
