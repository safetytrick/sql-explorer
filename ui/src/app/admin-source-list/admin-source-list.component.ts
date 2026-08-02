import { Component, OnInit } from '@angular/core';
import {SourceService} from '../source.service';
import {EnvironmentService, EnvironmentSummary} from '../environment.service';
import { MatSelectionList, MatListOption } from '@angular/material/list';
import { DatePipe } from '@angular/common';
import { ImportedSqlSource, PersistedSource } from '../api-types';

@Component({
    selector: 'app-admin-source-list',
    templateUrl: './admin-source-list.component.html',
    styleUrls: ['./admin-source-list.component.scss'],
    imports: [MatSelectionList, MatListOption, DatePipe]
})
export class AdminSourceListComponent implements OnInit {

  active!: ImportedSqlSource[];
  all!: PersistedSource[];
  currentEnvironment: string | null;
  environment: EnvironmentSummary | null = null;
  loading = false;

  constructor(private sourceService: SourceService, private environmentService: EnvironmentService) {
    this.currentEnvironment = environmentService.getCurrent();
  }

  ngOnInit() {
    this.refresh();
  }

  private refresh() {
    this.sourceService.getActiveSources()
      .subscribe(active => this.active = active);
    this.sourceService.getAllSources().subscribe(
      all => this.all = all);
    this.environmentService.getEnvironments().subscribe(list => {
      const name = this.currentEnvironment || list.defaultEnvironment;
      this.currentEnvironment = name;
      this.environment = list.environments.find(env => env.name === name) || null;
    });
  }

  isActive(source: { sourceRef: string }): boolean {
    return !!this.active && this.active.some(value => value.source.sourceRef === source.sourceRef);
  }

  applyChange(sourcesList: MatSelectionList) {
    const selected = sourcesList.selectedOptions.selected.map(value => value.value);
    this.loading = true;
    this.sourceService.loadSources(selected.map(value => ({ sourceRef: value }))).subscribe({
      next: complete => {
        console.log('Loaded: ' + selected.join(', '));
        this.loading = false;
        this.refresh();
      },
      error: () => {
        this.loading = false;
        this.refresh();
      }
    });
  }

  cancelChange(sourcesList: MatSelectionList) {
    sourcesList.options.forEach(option => {
      option.selected = this.isActive({'sourceRef': option.value});
    });
    console.log('Options Reset');
  }

  refreshSchema() {
    if (!this.currentEnvironment) {
      return;
    }
    this.environmentService.refreshSchema(this.currentEnvironment)
      .subscribe(() => this.refresh());
  }
}
