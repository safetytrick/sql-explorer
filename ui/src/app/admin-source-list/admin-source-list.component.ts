import {Component, OnInit} from '@angular/core';
import {SourceService} from '../source.service';
import {EnvironmentService, EnvironmentSummary} from '../environment.service';
import {DatePipe} from '@angular/common';
import {ImportedSqlSource, PersistedSource} from '../api-types';

@Component({
    selector: 'app-admin-source-list',
    templateUrl: './admin-source-list.component.html',
    styleUrls: ['./admin-source-list.component.scss'],
    imports: [DatePipe]
})
export class AdminSourceListComponent implements OnInit {

  active!: ImportedSqlSource[];
  all!: PersistedSource[];
  currentEnvironment: string | null;
  environment: EnvironmentSummary | null = null;
  loading = false;

  /** sourceRefs ticked in the list; seeded from the active sources. */
  selected = new Set<string>();

  constructor(private sourceService: SourceService, private environmentService: EnvironmentService) {
    this.currentEnvironment = environmentService.getCurrent();
  }

  ngOnInit() {
    this.refresh();
  }

  private refresh() {
    this.sourceService.getActiveSources()
      .subscribe(active => {
        this.active = active;
        this.resetSelection();
      });
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

  toggle(sourceRef: string) {
    if (this.selected.has(sourceRef)) {
      this.selected.delete(sourceRef);
    } else {
      this.selected.add(sourceRef);
    }
  }

  applyChange() {
    const selected = [...this.selected];
    this.loading = true;
    this.sourceService.loadSources(selected.map(value => ({ sourceRef: value }))).subscribe({
      next: () => {
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

  cancelChange() {
    this.resetSelection();
    console.log('Options Reset');
  }

  refreshSchema() {
    if (!this.currentEnvironment) {
      return;
    }
    this.environmentService.refreshSchema(this.currentEnvironment)
      .subscribe(() => this.refresh());
  }

  private resetSelection() {
    this.selected = new Set((this.active ?? []).map(value => value.source.sourceRef));
  }
}
