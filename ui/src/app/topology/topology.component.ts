import {Component, DestroyRef, inject, OnInit} from '@angular/core';
import {DatePipe, DecimalPipe, NgTemplateOutlet} from '@angular/common';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {skip} from 'rxjs';
import {EnvironmentService} from '../environment.service';
import {SqlHighlightDirective} from '../sql-highlight/sql-highlight.directive';
import {TopologyService} from './topology.service';
import {
  allServers, arrange, decodeTopologyParams, encodeTopologyParams, formatSeconds, KIND_LABELS, Load, Measure,
  percent, ServerStatements, shownInLegend, split, statusLabel, TopologyReport, TopologyServer, TopologyState
} from './topology';

/**
 * The environment's servers as config arranges them, with the share of reads
 * and writes each ran and its query seconds (sqlx-a9h). Every figure is the
 * server's own Performance Schema; nothing is merged across servers.
 */
@Component({
  selector: 'app-topology',
  templateUrl: './topology.component.html',
  styleUrls: ['./topology.component.scss'],
  imports: [RouterLink, DatePipe, DecimalPipe, NgTemplateOutlet, SqlHighlightDirective]
})
export class TopologyComponent implements OnInit {

  report: TopologyReport | null = null;
  loading = true;
  failed = false;
  state: TopologyState = {server: null, by: 'time'};
  statements: ServerStatements | null = null;
  statementsLoading = false;
  statementsFailed = false;

  protected readonly arrange = arrange;
  protected readonly split = split;
  protected readonly percent = percent;
  protected readonly formatSeconds = formatSeconds;
  protected readonly statusLabel = statusLabel;
  protected readonly KIND_LABELS = KIND_LABELS;
  protected readonly shownInLegend = shownInLegend;

  private readonly topology = inject(TopologyService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly environmentService = inject(EnvironmentService);

  ngOnInit() {
    this.state = decodeTopologyParams(this.route.snapshot.queryParamMap);
    this.load();
    // another environment's servers are not this one's: drop the selection
    this.environmentService.current$.pipe(skip(1), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.state = {...this.state, server: null};
      this.writeUrl();
      this.load();
    });
  }

  private load() {
    this.loading = true;
    this.failed = false;
    this.report = null;
    this.statements = null;
    this.topology.getTopology().subscribe({
      next: report => {
        this.report = report;
        this.loading = false;
        if (this.state.server && !allServers(report).some(s => s.name === this.state.server)) {
          this.state = {...this.state, server: null};
          this.writeUrl();
        }
        this.loadStatements();
      },
      // an empty page would read as "no servers", which a failed request cannot support
      error: () => { this.failed = true; this.loading = false; }
    });
  }

  private loadStatements() {
    this.statements = null;
    this.statementsFailed = false;
    const server = this.state.server;
    if (!server) {
      return;
    }
    this.statementsLoading = true;
    this.topology.getStatements(server).subscribe({
      next: statements => {
        if (this.state.server === server) {
          this.statements = statements;
          this.statementsLoading = false;
        }
      },
      error: () => { this.statementsFailed = true; this.statementsLoading = false; }
    });
  }

  select(server: TopologyServer) {
    this.state = {...this.state, server: this.state.server === server.name ? null : server.name};
    this.writeUrl();
    this.loadStatements();
  }

  setMeasure(by: Measure) {
    this.state = {...this.state, by};
    this.writeUrl();
  }

  private writeUrl() {
    this.router.navigate([], {relativeTo: this.route, queryParams: encodeTopologyParams(this.state),
      queryParamsHandling: 'merge', replaceUrl: true});
  }

  get selected(): TopologyServer | null {
    return allServers(this.report).find(server => server.name === this.state.server) ?? null;
  }

  /** The headline number: seconds, or executions when splitting by executions. */
  headline(load: Load | null | undefined): string {
    if (!load) {
      return '—';
    }
    return this.state.by === 'time' ? formatSeconds(load.seconds) : compact(load.executions) + ' exec';
  }

  /** Fraction of the environment's measure this server carries, for the size bar. */
  weight(server: TopologyServer): number {
    const total = this.state.by === 'time' ? this.report?.total.seconds : this.report?.total.executions;
    const mine = this.state.by === 'time' ? server.load?.seconds : server.load?.executions;
    return total && mine ? mine / total : 0;
  }

  /** Reads and writes as "R 62% · W 31%", the two figures the page exists for. */
  readWrite(load: Load | null | undefined): string {
    const segments = split(load, this.state.by);
    if (!segments.length) {
      return '';
    }
    const of = (kind: string) => percent(segments.find(s => s.kind === kind)?.fraction ?? 0);
    return 'R ' + of('READ') + ' · W ' + of('WRITE');
  }

  segmentTitle(kind: string, fraction: number, value: number): string {
    const amount = this.state.by === 'time' ? formatSeconds(value) : compact(value) + ' executions';
    return KIND_LABELS[kind as keyof typeof KIND_LABELS] + ': ' + percent(fraction) + ' (' + amount + ')';
  }
}

/** 1234567 → 1.2M. */
function compact(value: number): string {
  if (value >= 1e9) { return (value / 1e9).toFixed(1) + 'B'; }
  if (value >= 1e6) { return (value / 1e6).toFixed(1) + 'M'; }
  if (value >= 1e4) { return (value / 1e3).toFixed(0) + 'k'; }
  return String(value);
}
