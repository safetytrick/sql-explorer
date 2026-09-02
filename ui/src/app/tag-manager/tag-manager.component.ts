import { Component, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { TagColorsService, TagSummary } from '../tag-colors.service';
import { AnalyzedSqlService } from '../analyzed-sql.service';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { SchemaRollup, Tag, TableRollup } from '../api-types';
import { orderMembers, queryMembers, queryTargetOf, schemaMembers, TagMember, tableMembers } from './tag-members';
import {schemaTarget, tableTarget, type TagTarget} from '../tag-path';

const TAG_COLORS = ['#f5a623', '#2dd4bf', '#a78bfa', '#4a9eff', '#f56565', '#3dd68c', '#8b93a8'];

/** A thing that could be tagged, offered by the add-member picker. */
interface Target {
  label: string;
  target: TagTarget;
}

@Component({
  selector: 'app-tag-manager',
  templateUrl: './tag-manager.component.html',
  styleUrls: ['./tag-manager.component.scss'],
  imports: [FormsModule, DatePipe, RouterLink, PicoViewComponent]
})
export class TagManagerComponent implements OnInit {

  readonly colors = TAG_COLORS;

  tags: TagSummary[] = [];
  newTagName = '';
  selectedColor = TAG_COLORS[0];
  confirmingDelete: string | null = null;

  /** Which tag's members the detail pane is showing. Mirrored in `?tag=`. */
  selected: string | null = null;
  members: TagMember[] = [];
  loadingMembers = false;

  addOpen = false;
  addFilter = '';
  private targets: Target[] | null = null;

  constructor(private tagColorsService: TagColorsService,
              private sqlService: AnalyzedSqlService,
              private route: ActivatedRoute,
              private router: Router) { }

  ngOnInit() {
    const fromUrl = this.route.snapshot.queryParamMap.get('tag');
    this.tagColorsService.getSummaries(true).subscribe(tags => {
      this.tags = tags;
      // Auto-select so the detail pane — which is where the per-type counts
      // and the graph link now live — is never empty on arrival.
      const wanted = fromUrl && tags.some(tag => tag.name === fromUrl) ? fromUrl
        : (tags.length ? tags[0].name : null);
      if (wanted) {
        this.select(wanted);
      }
    });
  }

  private reload(keepSelection = true) {
    const previous = this.selected;
    this.tagColorsService.getSummaries(true).subscribe(tags => {
      this.tags = tags;
      const stillThere = keepSelection && previous && tags.some(tag => tag.name === previous);
      if (stillThere) {
        this.loadMembers(previous!);
      } else {
        this.select(tags.length ? tags[0].name : null);
      }
    });
  }

  summary(name: string | null): TagSummary | undefined {
    return this.tags.find(tag => tag.name === name);
  }

  /** Every member of a tag, across all three kinds. */
  total(tag: TagSummary): number {
    return tag.queries + tag.tables + tag.schemas + tag.other;
  }

  select(name: string | null) {
    this.selected = name;
    this.addOpen = false;
    this.addFilter = '';
    // Selection is view state, so it travels in the link (see CLAUDE.md).
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {tag: name || null},
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
    if (name) {
      const tag = this.summary(name);
      if (tag) {
        this.edit(tag);
      }
      this.loadMembers(name);
    } else {
      this.members = [];
    }
  }

  private loadMembers(name: string) {
    this.loadingMembers = true;
    forkJoin({
      schemas: this.sqlService.getSchemasByTag(name),
      tables: this.sqlService.getTablesByTag(name),
      queries: this.sqlService.getSqlByTag(name)
    }).pipe(
      // A SqlProfile carries no tags, so the association ids the detach button
      // needs have to be fetched separately. One batched call, not one per
      // query, skipped entirely when the tag has no queries — and answered
      // positionally, so it is zipped against `queries` by index (sqlx-3ai).
      switchMap(loaded => loaded.queries.length
        ? this.sqlService.getTagsForTargets(loaded.queries.map(queryTargetOf))
            .pipe(switchMap(byIndex => of({...loaded, byIndex})))
        : of({...loaded, byIndex: [] as Tag[][]}))
    ).subscribe(loaded => {
      if (this.selected !== name) {
        return; // a different tag was picked while this was in flight
      }
      this.members = orderMembers([
        ...schemaMembers(loaded.schemas, name),
        ...tableMembers(loaded.tables, name),
        ...queryMembers(loaded.queries, name, loaded.byIndex)
      ]);
      this.loadingMembers = false;
    });
  }

  detach(member: TagMember) {
    if (member.assocId === null) {
      return;
    }
    this.sqlService.removeTag(member.assocId).subscribe(() => this.reload());
    this.members = this.members.filter(existing => existing !== member);
  }

  // ── add member ──────────────────────────────────────────────────────────
  // `POST /tags` takes any path, so the server supports attaching a tag to a
  // schema, a table or a query alike. What it needs is a target to point at:
  // schemas and tables are small enough to offer as a list, a query is not —
  // those are tagged from the SQL view, where TagField already sits.

  openAdd() {
    this.addOpen = true;
    this.addFilter = '';
    if (this.targets === null) {
      forkJoin({
        schemas: this.sqlService.getSchemas(),
        tables: this.sqlService.getTables()
      }).subscribe(loaded => this.targets = [
        ...loaded.schemas.map((schema: SchemaRollup) =>
          ({label: schema.name, target: schemaTarget(schema.name)})),
        ...loaded.tables.map((table: TableRollup) => ({
          label: `${table.schema}.${table.name}`,
          target: tableTarget(table.schema, table.name)
        }))
      ]);
    }
  }

  get addOptions(): Target[] {
    if (!this.targets) {
      return [];
    }
    const already = new Set(this.members.map(member => member.label));
    const query = this.addFilter.trim().toLowerCase();
    return this.targets
      .filter(target => !already.has(target.label))
      .filter(target => !query || target.label.toLowerCase().includes(query))
      .slice(0, 40);
  }

  attach(target: Target) {
    if (!this.selected) {
      return;
    }
    this.addOpen = false;
    this.addFilter = '';
    this.sqlService.addTag(this.selected, target.target).subscribe(() => this.reload());
  }

  // ── definition editing (unchanged) ──────────────────────────────────────

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

  /** Selecting a tag also loads it into the editor so its color can change. */
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
    this.tagColorsService.deleteTag(name).subscribe(() => this.reload(false));
  }
}
