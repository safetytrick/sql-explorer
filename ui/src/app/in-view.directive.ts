import {Directive, ElementRef, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';

/**
 * Emits `appInView` whenever the element scrolls to within `inViewMargin` of
 * being visible — the trigger for loading the next page before the reader
 * reaches the end (sqlx-ywy.6).
 *
 * Root is the viewport; an IntersectionObserver still accounts for clipping by
 * scrolling ancestors, so this works inside a scrolling pane too.
 */
@Directive({ selector: '[appInView]' })
export class InViewDirective implements OnInit, OnDestroy {
  @Input() inViewMargin = '400px';
  @Output() appInView = new EventEmitter<void>();

  private observer?: IntersectionObserver;

  constructor(private element: ElementRef<HTMLElement>) {}

  ngOnInit() {
    if (typeof IntersectionObserver === 'undefined') { return; }
    this.observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) {
        this.appInView.emit();
      }
    }, { rootMargin: this.inViewMargin });
    this.observer.observe(this.element.nativeElement);
  }

  ngOnDestroy() {
    this.observer?.disconnect();
  }
}
