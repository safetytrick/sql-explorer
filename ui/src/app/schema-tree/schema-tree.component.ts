import { Component, OnDestroy, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription } from 'rxjs';
import { SchemaLensService, SchemaNode } from '../schema-lens.service';
import { EnvironmentService } from '../environment.service';
import { QueryFacets } from '../query-search.service';
import { QueryScopeService, tableKey } from '../query-scope.service';

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

  /** In filter mode the tree scopes the queries workbench instead of navigating. */
  active = false;
  private facets: QueryFacets = { tables: {}, schemas: {} };

  private subscriptions = new Subscription();

  constructor(private schemaLensService: SchemaLensService,
              private environmentService: EnvironmentService,
              private queryScopeService: QueryScopeService) { }

  ngOnInit() {
    // the header interceptor scopes the request; reload whenever the selection changes
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.reload()));
    this.subscriptions.add(this.queryScopeService.active$.subscribe(active => this.active = active));
    this.subscriptions.add(this.queryScopeService.facets$.subscribe(facets => this.facets = facets));
    this.subscriptions.add(this.queryScopeService.scope$.subscribe(() => { /* re-render selection */ }));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
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

  // ── filter mode (queries workbench scope) ──

  tableCount(schema: string, table: string): number {
    return this.facets.tables[tableKey(schema, table)] ?? 0;
  }

  schemaCount(schema: string): number {
    return this.facets.schemas[schema.toLowerCase()] ?? 0;
  }

  /** Once a search has populated facets, zero-match rows dim; before that, nothing dims. */
  private get facetsLoaded(): boolean {
    return Object.keys(this.facets.tables).length > 0;
  }

  isTableDimmed(schema: string, table: string): boolean {
    return this.facetsLoaded
      && this.tableCount(schema, table) === 0
      && !this.isTableSelected(schema, table);
  }

  isTableSelected(schema: string, table: string): boolean {
    return this.queryScopeService.isTableSelected(tableKey(schema, table));
  }

  isSchemaSelected(schema: string): boolean {
    return this.queryScopeService.isSchemaSelected(schema);
  }

  toggleTable(schema: string, table: string) {
    this.queryScopeService.toggleTable(tableKey(schema, table));
  }

  toggleSchema(schema: string) {
    this.queryScopeService.toggleSchema(schema);
  }
}
