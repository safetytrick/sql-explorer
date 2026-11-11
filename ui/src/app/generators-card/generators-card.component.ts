import {Component, OnInit} from '@angular/core';
import {DatePipe} from '@angular/common';
import {RouterLink} from '@angular/router';
import {AnnotationService, GeneratorInfo} from '../annotation.service';
import {GLOSSARY} from '../glossary';

/**
 * Which generators this environment uses, as a person has confirmed them
 * (sqlx-n5j.12).
 *
 * Analysis records every generator whose signatures a statement matches, but
 * only as candidates: two generators can write the same shape. Confirming one
 * here turns its matches into asserted origins on the query page and in the
 * workbench — at once, with no re-analysis, because the confirmation is
 * applied when a query is read. Each answer is an annotation on the
 * environment, appended rather than overwritten, so who decided and when stays
 * on record.
 *
 * On the Sources page rather than Settings: this is a fact about the
 * environment, shared with everyone, not a preference kept in one browser.
 */
@Component({
  selector: 'app-generators-card',
  templateUrl: './generators-card.component.html',
  styleUrls: ['./generators-card.component.scss'],
  imports: [DatePipe, RouterLink]
})
export class GeneratorsCardComponent implements OnInit {
  generators: GeneratorInfo[] = [];
  failed = false;
  saving: string | null = null;
  readonly definition = GLOSSARY.generator;

  constructor(private annotations: AnnotationService) {}

  ngOnInit() {
    this.annotations.generators().subscribe({
      next: generators => this.generators = generators ?? [],
      error: () => this.failed = true
    });
  }

  set(generator: GeneratorInfo, used: boolean) {
    if (this.saving) { return; }
    this.saving = generator.name;
    this.annotations.confirmGenerator(generator.name, used).subscribe({
      next: confirmation => {
        this.generators = this.generators.map(existing => existing.name === generator.name
          ? {...existing, status: used ? 'used' : 'not-used', confirmation}
          : existing);
        this.saving = null;
      },
      error: () => this.saving = null
    });
  }

  /** The workbench, filtered to this generator's matches. */
  queriesParams(generator: GeneratorInfo): Record<string, string> {
    return {generator: generator.name};
  }
}
