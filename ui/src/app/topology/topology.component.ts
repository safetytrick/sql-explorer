import {Component, DestroyRef, inject, OnInit} from '@angular/core';
import {DecimalPipe, NgTemplateOutlet} from '@angular/common';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {skip} from 'rxjs';
import {EnvironmentService} from '../environment.service';
import {TopologyService} from './topology.service';
import {
  arrange, decodeTopologyParams, encodeTopologyParams, formatSeconds, KIND_LABELS, Load, Measure, opensQueries,
  percent, serverQueriesParams, shownInLegend, split, statusLabel, TopologyReport, TopologyServer, TopologyState
} from './topology';

/**
 * The environment's servers as config arranges them, with the share of reads
 * and writes each ran and its query seconds (sqlx-a9h). Every figure is the
 * server's own Performance Schema; nothing is merged across servers. A
 * server opens the queries workbench scoped to its source, which reads the
 * same per-source corpus these figures come from (sqlx-a9h.1).
 */
@Component({
  selector: 'app-topology',
  templateUrl: './topology.component.html',
  styleUrls: ['./topology.component.scss'],
  imports: [RouterLink, DecimalPipe, NgTemplateOutlet]
})
export class TopologyComponent implements OnInit {

  report: TopologyReport | null = null;
  loading = true;
  failed = false;
  state: TopologyState = {by: 'time'};

  protected readonly arrange = arrange;
  protected readonly split = split;
  protected readonly percent = percent;
  protected readonly formatSeconds = formatSeconds;
  protected readonly statusLabel = statusLabel;
  protected readonly KIND_LABELS = KIND_LABELS;
  protected readonly shownInLegend = shownInLegend;
  protected readonly opensQueries = opensQueries;
  protected readonly serverQueriesParams = serverQueriesParams;

  private readonly topology = inject(TopologyService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly environmentService = inject(EnvironmentService);

  ngOnInit() {
    this.state = decodeTopologyParams(this.route.snapshot.queryParamMap);
    // an old link's var-server is dropped: a server's statements live in the workbench now
    if (this.route.snapshot.queryParamMap.has('var-server')) {
      this.writeUrl();
    }
    this.load();
    this.environmentService.current$.pipe(skip(1), takeUntilDestroyed(this.destroyRef)).subscribe(() => this.load());
  }

  private load() {
    this.loading = true;
    this.failed = false;
    this.report = null;
    this.topology.getTopology().subscribe({
      next: report => {
        this.report = report;
        this.loading = false;
      },
      // an empty page would read as "no servers", which a failed request cannot support
      error: () => { this.failed = true; this.loading = false; }
    });
  }

  setMeasure(by: Measure) {
    this.state = {...this.state, by};
    this.writeUrl();
  }

  private writeUrl() {
    this.router.navigate([], {relativeTo: this.route, queryParams: encodeTopologyParams(this.state),
      queryParamsHandling: 'merge', replaceUrl: true});
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
