import {Component, inject, Input, OnChanges, OnDestroy} from '@angular/core';
import {DatePipe} from '@angular/common';
import {Subscription} from 'rxjs';
import {Annotation, AnnotationService} from '../annotation.service';
import {targetKey, type TagTarget} from '../tag-path';
import {GLOSSARY} from '../glossary';
import {EnvironmentService} from '../environment.service';
import {UserService} from '../user.service';

/**
 * The current annotations on one target, beside its tags (sqlx-n5j.13).
 *
 * Read-mostly. Each fact is one line — `namespace · key = value` — with its
 * confidence, and hover text saying who stated it, when, and why. A fact that
 * is not yet confirmed can be confirmed in one click, which appends the same
 * value as the current person's confirmed answer; nothing is edited in place.
 *
 * Renders nothing at all when the target has no facts, so it costs no space
 * on the many objects nobody has annotated.
 */
@Component({
  selector: 'app-annotations-view',
  templateUrl: './annotations-view.component.html',
  styleUrls: ['./annotations-view.component.scss'],
  imports: [DatePipe]
})
export class AnnotationsViewComponent implements OnChanges, OnDestroy {
  private readonly users = inject(UserService);

  /** Tags, notes and confirmations are known to be refused here: a visitor (sqlx-387). */
  get readOnly(): boolean {
    return this.users.denied('commentary:write');
  }

  @Input({required: true}) target!: TagTarget;

  annotations: Annotation[] = [];
  failed = false;
  confirming = new Set<number>();
  readonly definition = GLOSSARY.annotations;
  private subscription = new Subscription();
  private readonly environmentService = inject(EnvironmentService, {optional: true});
  /** The target last asked about, by value, in the environment it was asked in. */
  private loadedKey: string | null = null;

  constructor(private service: AnnotationService) {}

  /**
   * Asks again only when the target is a different one. Hosts bind
   * `[target]="tableTarget(schema, table)"`, a fresh object on every change
   * detection; comparing by reference re-asked on each pass, each answer ran
   * another pass, and an open page posted about a thousand requests a second
   * until nginx ran out of ports.
   */
  ngOnChanges() {
    const key = (this.environmentService?.getCurrent() ?? '') + '\u0000' + targetKey(this.target);
    if (key === this.loadedKey) { return; }
    this.loadedKey = key;
    this.subscription.unsubscribe();
    this.subscription = new Subscription();
    this.annotations = [];
    this.failed = false;
    this.subscription.add(this.service.currentFor([this.target]).subscribe({
      next: answer => this.annotations = answer?.[0] ?? [],
      error: () => this.failed = true
    }));
  }

  ngOnDestroy() {
    this.subscription.unsubscribe();
  }

  /** Who said so, for hover text: the rule or sampler by name, or the person. */
  source(annotation: Annotation): string {
    if (annotation.sourceKind === 'person') {
      return annotation.author?.email || 'someone';
    }
    return annotation.sourceKind + ' ' + (annotation.sourceName ?? '');
  }

  title(annotation: Annotation): string {
    const lines = [annotation.confidence + ', by ' + this.source(annotation)];
    if (annotation.basis) { lines.push(annotation.basis); }
    return lines.join('\n');
  }

  confirm(annotation: Annotation) {
    if (this.confirming.has(annotation.id)) { return; }
    this.confirming.add(annotation.id);
    this.subscription.add(this.service.confirm(annotation.id).subscribe({
      next: confirmed => {
        this.annotations = this.annotations.map(existing => existing.id === annotation.id ? confirmed : existing);
        this.confirming.delete(annotation.id);
      },
      error: () => this.confirming.delete(annotation.id)
    }));
  }
}
