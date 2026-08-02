import { Component, ElementRef, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { describeDateExpr, isValidDateExpr } from './date-math';

export interface DateRangeValue {
  from: string | null;   // expression: 'now-6d', '2026-07-01', … (resolve with resolveDateExpr)
  to: string | null;
}

interface QuickRange {
  label: string;
  from: string;
  to: string;
}

/**
 * Grafana-style date range selector: a trigger button that opens a panel with
 * expression inputs (now, now-6d, -2w, now/M, absolute dates) and quick ranges.
 * Emits raw expressions so relative ranges stay live; callers resolve them at
 * request time via resolveDateExpr().
 */
@Component({
  selector: 'app-date-range-selector',
  templateUrl: './date-range-selector.component.html',
  styleUrls: ['./date-range-selector.component.scss'],
  imports: [FormsModule]
})
export class DateRangeSelectorComponent {

  @Input() from: string | null = null;
  @Input() to: string | null = null;
  @Output() rangeChange = new EventEmitter<DateRangeValue>();

  open = false;
  draftFrom = '';
  draftTo = '';
  panelTop = 0;
  panelLeft = 0;

  readonly quickRanges: QuickRange[] = [
    { label: 'Last 24 hours', from: 'now-24h', to: 'now' },
    { label: 'Last 3 days', from: 'now-3d', to: 'now' },
    { label: 'Last 7 days', from: 'now-7d', to: 'now' },
    { label: 'Last 14 days', from: 'now-14d', to: 'now' },
    { label: 'Last 30 days', from: 'now-30d', to: 'now' },
    { label: 'Last 90 days', from: 'now-90d', to: 'now' },
    { label: 'Last 6 months', from: 'now-6M', to: 'now' },
    { label: 'Last 1 year', from: 'now-1y', to: 'now' },
    { label: 'Month to date', from: 'now/M', to: 'now' },
    { label: 'Year to date', from: 'now/y', to: 'now' },
  ];

  constructor(private host: ElementRef<HTMLElement>) { }

  get summary(): string {
    if (!this.from && !this.to) { return 'any time'; }
    return `${this.from || '…'} → ${this.to || 'now'}`;
  }

  toggle(trigger: HTMLElement) {
    if (this.open) {
      this.open = false;
      return;
    }
    const rect = trigger.getBoundingClientRect();
    const PANEL_WIDTH = 360;
    this.panelTop = Math.round(rect.bottom + 4);
    this.panelLeft = Math.round(Math.max(8, Math.min(rect.left, window.innerWidth - PANEL_WIDTH - 8)));
    this.draftFrom = this.from ?? '';
    this.draftTo = this.to ?? '';
    this.open = true;
  }

  apply() {
    if (this.invalid(this.draftFrom) || this.invalid(this.draftTo)) { return; }
    this.emit(this.draftFrom.trim() || null, this.draftTo.trim() || null);
  }

  setQuick(range: QuickRange) {
    this.emit(range.from, range.to);
  }

  clear() {
    this.emit(null, null);
  }

  isActiveQuick(range: QuickRange): boolean {
    return range.from === this.from && range.to === this.to;
  }

  invalid(expr: string): boolean {
    return !isValidDateExpr(expr);
  }

  preview(expr: string, roundUp = false): string {
    return describeDateExpr(expr, roundUp);
  }

  /** Date-picker helper: current absolute date part of the draft, if any. */
  dateOf(expr: string): string {
    const match = /^(\d{4}-\d{2}-\d{2})/.exec(expr.trim());
    return match ? match[1] : '';
  }

  @HostListener('document:mousedown', ['$event'])
  onDocumentMouseDown(event: MouseEvent) {
    if (this.open && !this.host.nativeElement.contains(event.target as Node)) {
      this.open = false;
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.open = false;
  }

  private emit(from: string | null, to: string | null) {
    this.from = from;
    this.to = to;
    this.rangeChange.emit({ from, to });
    this.open = false;
  }
}
