import { Component, OnDestroy, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription } from 'rxjs';
import { SchemaLensService, SchemaNode } from '../schema-lens.service';
import { EnvironmentService } from '../environment.service';

@Component({
  selector: 'app-schema-tree',
  templateUrl: './schema-tree.component.html',
  styleUrls: ['./schema-tree.component.scss'],
  imports: [RouterLink, RouterLinkActive]
})
export class SchemaTreeComponent implements OnInit, OnDestroy {

  schemas: SchemaNode[] = [];
  filter = '';
  loading = true;

  private environmentChanges?: Subscription;

  constructor(private schemaLensService: SchemaLensService,
              private environmentService: EnvironmentService) { }

  ngOnInit() {
    // the header interceptor scopes the request; reload whenever the selection changes
    this.environmentChanges = this.environmentService.current$.subscribe(() => this.reload());
  }

  ngOnDestroy() {
    this.environmentChanges?.unsubscribe();
  }

  private reload() {
    this.loading = true;
    this.schemaLensService.getSchemaTree().subscribe({
      next: tree => {
        this.schemas = tree.schemas;
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  visibleSchemas(): SchemaNode[] {
    const needle = this.filter.trim().toLowerCase();
    if (!needle) {
      return this.schemas;
    }
    return this.schemas
      .map(schema => ({
        name: schema.name,
        tables: schema.tables.filter(table => table.name.toLowerCase().includes(needle))
      }))
      .filter(schema => schema.tables.length > 0);
  }
}
