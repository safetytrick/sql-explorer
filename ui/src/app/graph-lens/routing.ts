/**
 * Edge routing through the grid's gutters, the way cables run in trays
 * (sqlx-aoy.14).
 *
 * <p>A straight line between two tables crosses whatever sits between them,
 * and at the density a real schema reaches, straight lines are unreadable.
 * The grid layout leaves clear space between every pair of neighbouring
 * cells at every level, so that space is used as a network of channels:
 *
 * <ul>
 *   <li>every container (the environment, a schema, an aggregate) has a
 *       channel down the middle of each gutter between its columns and rows,
 *       and a ring just inside its padding;</li>
 *   <li>a container's channels run on past its edge to its parent's nearest
 *       channels, which is how a route climbs out of an aggregate and into the
 *       schema around it;</li>
 *   <li>each table has a stub along its centre lines out to the channels
 *       around it, and a route starts and ends at a table's centre. No route
 *       passes through a table it does not start or end at.</li>
 * </ul>
 *
 * <p>Routes are orthogonal shortest paths over that network (A*), where a bend
 * costs extra and a stretch another route already uses costs less. The
 * discount is what bundles them: routes heading the same way converge on a
 * shared channel instead of each taking its own nearly-parallel path.
 * Routes sharing a stretch are then drawn side by side in lanes, each route
 * keeping one lane for the whole straight run so it never jogs mid-channel.
 *
 * <p>Pure: boxes and requests in, polylines out.
 */
import {LayoutBox} from './grid-layout';

export interface Point {
  x: number;
  y: number;
}

/** A route wanted between two leaf boxes, by their keys. */
export interface RouteRequest {
  id: string;
  from: string;
  to: string;
}

export interface RoutingOptions {
  /** Extra cost of a turn, in world units. */
  bendCost?: number;
  /** Cost multiplier for a stretch another route already uses (< 1 bundles). */
  shareFactor?: number;
  /** Space between neighbouring lanes in a channel, at most. */
  laneSpacing?: number;
  /** Clear space around the outermost boxes, for the outer ring road. */
  margin?: number;
}

const DEFAULTS: Required<RoutingOptions> = { bendCost: 28, shareFactor: 0.6, laneSpacing: 4, margin: 40 };

/** A straight channel: vertical at x = c from a to b, or horizontal at y = c. */
interface Segment {
  vertical: boolean;
  c: number;
  a: number;
  b: number;
  /** How wide the clear space around the channel is, which bounds its lanes. */
  width: number;
}

/** Directions, indexed so that the opposite of d is d ^ 1. */
const EAST = 0, WEST = 1, SOUTH = 2, NORTH = 3;

const EPS = 0.01;
const coord = (value: number) => Math.round(value * 100) / 100;

/**
 * Route every request through the boxes' gutters. Requests are routed in the
 * order given, and earlier routes attract later ones, so put the routes that
 * matter most first. A request whose ends are missing or the same box gets no
 * route.
 */
export function routeEdges(boxes: LayoutBox[], requests: RouteRequest[],
                           options: RoutingOptions = {}): Map<string, Point[]> {
  const settings = { ...DEFAULTS, ...options };
  const routes = new Map<string, Point[]>();
  if (!boxes.length || !requests.length) { return routes; }

  const network = buildNetwork(boxes, settings.margin);
  const used = new Uint8Array(network.count * 4);
  const paths: { id: string; path: number[]; from: LayoutBox; to: LayoutBox }[] = [];
  for (const request of requests) {
    const from = network.leaves.get(request.from);
    const to = network.leaves.get(request.to);
    if (!from || !to || from === to) { continue; }
    const start = network.terminalOf.get(request.from)!;
    const goal = network.terminalOf.get(request.to)!;
    const path = search(network, used, start, goal, settings);
    if (!path) { continue; }
    for (let i = 1; i < path.length; i++) {
      const dir = direction(network, path[i - 1], path[i]);
      used[path[i - 1] * 4 + dir] = 1;
      used[path[i] * 4 + (dir ^ 1)] = 1;
    }
    paths.push({ id: request.id, path, from, to });
  }

  // Runs: the straight stretches of each path, which is what lanes attach to.
  const runsOf = paths.map(({ path }) => runs(network, path));
  const offsets = assignLanes(runsOf, network.widthAt, settings.laneSpacing);
  paths.forEach(({ id, from, to }, index) => {
    routes.set(id, polyline(runsOf[index], offsets[index], from, to));
  });
  return routes;
}

// ── the channel network ──

interface Network {
  count: number;
  xs: Float64Array;
  ys: Float64Array;
  /** Neighbour of vertex v in direction d at [v * 4 + d], or -1. */
  next: Int32Array;
  terminals: Uint8Array;
  terminalOf: Map<string, number>;
  leaves: Map<string, LayoutBox>;
  /** Clear width of the channel at a coordinate, keyed `v:x` or `h:y`. */
  widthAt: Map<string, number>;
}

interface ContainerLines {
  vertical: number[];
  horizontal: number[];
}

function buildNetwork(boxes: LayoutBox[], margin: number): Network {
  const segments: Segment[] = [];
  const leaves = new Map<string, LayoutBox>();
  const centres: { key: string; x: number; y: number }[] = [];

  const minX = Math.min(...boxes.map(box => box.x));
  const minY = Math.min(...boxes.map(box => box.y));
  const maxX = Math.max(...boxes.map(box => box.x + box.w));
  const maxY = Math.max(...boxes.map(box => box.y + box.h));
  const root: LayoutBox = {
    key: '', slot: { col: 0, row: 0 },
    x: minX - margin * 2, y: minY - margin * 2,
    w: maxX - minX + margin * 4, h: maxY - minY + margin * 4,
    children: boxes
  };

  const visit = (container: LayoutBox, parent: ContainerLines | null) => {
    const lines = containerLines(container, segments);
    if (parent) { extendToParent(container, lines, parent, segments); }
    for (const child of container.children) {
      if (child.children.length) {
        visit(child, lines);
      } else {
        leaves.set(child.key, child);
        centres.push(leafStubs(child, lines, segments));
      }
    }
  };
  visit(root, null);

  // Vertices are where a vertical channel meets a horizontal one.
  const ids = new Map<string, number>();
  const xs: number[] = [];
  const ys: number[] = [];
  const vertex = (x: number, y: number) => {
    const key = `${coord(x)},${coord(y)}`;
    let id = ids.get(key);
    if (id === undefined) {
      id = xs.length;
      ids.set(key, id);
      xs.push(coord(x));
      ys.push(coord(y));
    }
    return id;
  };
  const vertical = segments.filter(segment => segment.vertical);
  const horizontal = segments.filter(segment => !segment.vertical);
  const onSegment = new Map<Segment, number[]>(segments.map(segment => [segment, []]));
  for (const v of vertical) {
    for (const h of horizontal) {
      if (v.c >= h.a - EPS && v.c <= h.b + EPS && h.c >= v.a - EPS && h.c <= v.b + EPS) {
        const id = vertex(v.c, h.c);
        onSegment.get(v)!.push(id);
        onSegment.get(h)!.push(id);
      }
    }
  }

  const count = xs.length;
  const next = new Int32Array(count * 4).fill(-1);
  const link = (from: number, dir: number, to: number) => {
    const distance = (a: number, b: number) => Math.abs(xs[a] - xs[b]) + Math.abs(ys[a] - ys[b]);
    const current = next[from * 4 + dir];
    if (current < 0 || distance(from, to) < distance(from, current)) { next[from * 4 + dir] = to; }
  };
  const widthAt = new Map<string, number>();
  for (const segment of segments) {
    const key = `${segment.vertical ? 'v' : 'h'}:${coord(segment.c)}`;
    widthAt.set(key, Math.min(widthAt.get(key) ?? Infinity, segment.width));
    const along = [...new Set(onSegment.get(segment)!)]
      .sort((a, b) => segment.vertical ? ys[a] - ys[b] : xs[a] - xs[b]);
    for (let i = 1; i < along.length; i++) {
      link(along[i - 1], segment.vertical ? SOUTH : EAST, along[i]);
      link(along[i], segment.vertical ? NORTH : WEST, along[i - 1]);
    }
  }

  const terminals = new Uint8Array(count);
  const terminalOf = new Map<string, number>();
  for (const centre of centres) {
    const id = ids.get(`${coord(centre.x)},${coord(centre.y)}`);
    if (id !== undefined) {
      terminals[id] = 1;
      terminalOf.set(centre.key, id);
    }
  }
  for (const key of [...leaves.keys()]) {
    if (!terminalOf.has(key)) { leaves.delete(key); }
  }
  return { count, xs: Float64Array.from(xs), ys: Float64Array.from(ys), next, terminals, terminalOf, leaves, widthAt };
}

/**
 * A container's own channels: one down each gutter between its columns and
 * rows, and a ring inside its padding. Vertical channels span the ring top
 * to bottom, horizontal ones left to right, so they all meet.
 */
function containerLines(container: LayoutBox, segments: Segment[]): ContainerLines {
  const children = container.children;
  const innerLeft = Math.min(...children.map(child => child.x));
  const innerTop = Math.min(...children.map(child => child.y));
  const innerRight = Math.max(...children.map(child => child.x + child.w));
  const innerBottom = Math.max(...children.map(child => child.y + child.h));
  // the right and bottom padding are the plain pad; the top also holds the label strip
  const padX = container.x + container.w - innerRight;
  const padY = container.y + container.h - innerBottom;

  const gutters = (starts: number[], ends: Map<number, number>, first: number, last: number, pad: number) => {
    const lines: { at: number; width: number }[] = [{ at: first - pad / 2, width: pad }];
    for (let i = 1; i < starts.length; i++) {
      const before = ends.get(starts[i - 1])!;
      lines.push({ at: (before + starts[i]) / 2, width: starts[i] - before });
    }
    lines.push({ at: last + pad / 2, width: pad });
    return lines;
  };
  // Cells are top-left aligned in their grid cell, so a column is the set of
  // cells sharing an x, and it ends at the widest of them.
  const bands = (start: (box: LayoutBox) => number, size: (box: LayoutBox) => number) => {
    const ends = new Map<number, number>();
    for (const child of children) {
      const at = coord(start(child));
      ends.set(at, Math.max(ends.get(at) ?? -Infinity, start(child) + size(child)));
    }
    return { starts: [...ends.keys()].sort((a, b) => a - b), ends };
  };
  const columns = bands(box => box.x, box => box.w);
  const rows = bands(box => box.y, box => box.h);
  const vertical = gutters(columns.starts, columns.ends, innerLeft, innerRight, padX);
  const horizontal = gutters(rows.starts, rows.ends, innerTop, innerBottom, padY);
  const top = horizontal[0].at;
  const bottom = horizontal[horizontal.length - 1].at;
  const left = vertical[0].at;
  const right = vertical[vertical.length - 1].at;
  for (const line of vertical) { segments.push({ vertical: true, c: line.at, a: top, b: bottom, width: line.width }); }
  for (const line of horizontal) { segments.push({ vertical: false, c: line.at, a: left, b: right, width: line.width }); }
  return { vertical: vertical.map(line => line.at), horizontal: horizontal.map(line => line.at) };
}

/**
 * Carry a container's channels out through its edge to the parent's nearest
 * channels on each side. The space crossed is the container's own grid cell
 * and half a gutter, which nothing else occupies.
 */
function extendToParent(container: LayoutBox, lines: ContainerLines, parent: ContainerLines, segments: Segment[]) {
  const above = Math.max(...parent.horizontal.filter(y => y <= container.y));
  const below = Math.min(...parent.horizontal.filter(y => y >= container.y + container.h));
  const before = Math.max(...parent.vertical.filter(x => x <= container.x));
  const after = Math.min(...parent.vertical.filter(x => x >= container.x + container.w));
  const top = lines.horizontal[0];
  const bottom = lines.horizontal[lines.horizontal.length - 1];
  const left = lines.vertical[0];
  const right = lines.vertical[lines.vertical.length - 1];
  for (const x of lines.vertical) {
    if (Number.isFinite(above)) { segments.push({ vertical: true, c: x, a: above, b: top, width: 0 }); }
    if (Number.isFinite(below)) { segments.push({ vertical: true, c: x, a: bottom, b: below, width: 0 }); }
  }
  for (const y of lines.horizontal) {
    if (Number.isFinite(before)) { segments.push({ vertical: false, c: y, a: before, b: left, width: 0 }); }
    if (Number.isFinite(after)) { segments.push({ vertical: false, c: y, a: right, b: after, width: 0 }); }
  }
}

/** A table's stubs: its centre lines, out to the channels either side of it. */
function leafStubs(leaf: LayoutBox, lines: ContainerLines, segments: Segment[]): { key: string; x: number; y: number } {
  const cx = leaf.x + leaf.w / 2;
  const cy = leaf.y + leaf.h / 2;
  const before = Math.max(...lines.vertical.filter(x => x <= leaf.x));
  const after = Math.min(...lines.vertical.filter(x => x >= leaf.x + leaf.w));
  const above = Math.max(...lines.horizontal.filter(y => y <= leaf.y));
  const below = Math.min(...lines.horizontal.filter(y => y >= leaf.y + leaf.h));
  segments.push({ vertical: false, c: cy, a: before, b: after, width: leaf.h });
  segments.push({ vertical: true, c: cx, a: above, b: below, width: leaf.w });
  return { key: leaf.key, x: cx, y: cy };
}

// ── search ──

function direction(network: Network, from: number, to: number): number {
  for (let dir = 0; dir < 4; dir++) {
    if (network.next[from * 4 + dir] === to) { return dir; }
  }
  throw new Error('not neighbours');
}

/**
 * A* over (vertex, direction of arrival), so a turn can be charged for.
 * The heuristic is the discounted Manhattan distance, which no path can beat,
 * so the route found is the cheapest. A table's centre is a dead end unless
 * it is where this route starts or ends.
 */
function search(network: Network, used: Uint8Array, start: number, goal: number,
                settings: Required<RoutingOptions>): number[] | null {
  const { xs, ys, next, terminals } = network;
  const states = network.count * 4;
  const cost = new Float64Array(states).fill(Infinity);
  const previous = new Int32Array(states).fill(-1);
  const closed = new Uint8Array(states);
  const heap = new MinHeap();
  const estimate = (v: number) => settings.shareFactor * (Math.abs(xs[v] - xs[goal]) + Math.abs(ys[v] - ys[goal]));
  const step = (from: number, dir: number, to: number) => {
    const length = Math.abs(xs[from] - xs[to]) + Math.abs(ys[from] - ys[to]);
    return length * (used[from * 4 + dir] ? settings.shareFactor : 1);
  };

  for (let dir = 0; dir < 4; dir++) {
    const to = next[start * 4 + dir];
    if (to < 0) { continue; }
    const state = to * 4 + dir;
    const value = step(start, dir, to);
    if (value < cost[state]) {
      cost[state] = value;
      previous[state] = -2 - start;   // marks "came from the start vertex"
      heap.push(state, value + estimate(to));
    }
  }

  while (heap.size) {
    const state = heap.pop();
    if (closed[state]) { continue; }
    closed[state] = 1;
    const vertex = state >> 2;
    const arrived = state & 3;
    if (vertex === goal) { return unwind(previous, state, start); }
    if (terminals[vertex]) { continue; }
    for (let dir = 0; dir < 4; dir++) {
      if (dir === (arrived ^ 1)) { continue; }
      const to = next[vertex * 4 + dir];
      if (to < 0) { continue; }
      const nextState = to * 4 + dir;
      const value = cost[state] + step(vertex, dir, to) + (dir === arrived ? 0 : settings.bendCost);
      if (value < cost[nextState]) {
        cost[nextState] = value;
        previous[nextState] = state;
        heap.push(nextState, value + estimate(to));
      }
    }
  }
  return null;
}

function unwind(previous: Int32Array, end: number, start: number): number[] {
  const path: number[] = [];
  let state = end;
  while (state >= 0) {
    path.push(state >> 2);
    state = previous[state];
  }
  path.push(start);
  return path.reverse();
}

class MinHeap {
  private items: number[] = [];
  private priorities: number[] = [];

  get size() { return this.items.length; }

  push(item: number, priority: number) {
    this.items.push(item);
    this.priorities.push(priority);
    let at = this.items.length - 1;
    while (at > 0) {
      const parent = (at - 1) >> 1;
      if (this.priorities[parent] <= this.priorities[at]) { break; }
      this.swap(at, parent);
      at = parent;
    }
  }

  pop(): number {
    const top = this.items[0];
    const lastItem = this.items.pop()!;
    const lastPriority = this.priorities.pop()!;
    if (this.items.length) {
      this.items[0] = lastItem;
      this.priorities[0] = lastPriority;
      let at = 0;
      for (;;) {
        const left = at * 2 + 1;
        const right = left + 1;
        let smallest = at;
        if (left < this.items.length && this.priorities[left] < this.priorities[smallest]) { smallest = left; }
        if (right < this.items.length && this.priorities[right] < this.priorities[smallest]) { smallest = right; }
        if (smallest === at) { break; }
        this.swap(at, smallest);
        at = smallest;
      }
    }
    return top;
  }

  private swap(a: number, b: number) {
    [this.items[a], this.items[b]] = [this.items[b], this.items[a]];
    [this.priorities[a], this.priorities[b]] = [this.priorities[b], this.priorities[a]];
  }
}

// ── lanes ──

/** A straight stretch of a route: along x = c (vertical) or y = c, from `a` to `b` in travel order. */
interface Run {
  vertical: boolean;
  c: number;
  a: number;
  b: number;
}

function runs(network: Network, path: number[]): Run[] {
  const { xs, ys } = network;
  const result: Run[] = [];
  for (let i = 1; i < path.length; i++) {
    const vertical = xs[path[i]] === xs[path[i - 1]];
    const last = result[result.length - 1];
    if (last && last.vertical === vertical) {
      last.b = vertical ? ys[path[i]] : xs[path[i]];
    } else {
      result.push(vertical
        ? { vertical, c: xs[path[i]], a: ys[path[i - 1]], b: ys[path[i]] }
        : { vertical, c: ys[path[i]], a: xs[path[i - 1]], b: xs[path[i]] });
    }
  }
  return result;
}

/**
 * Give each run a lane in its channel: runs on the same line that overlap get
 * different lanes (interval partitioning, as in channel routing), and lanes
 * are centred on the channel and spaced to fit its width. A route keeps its
 * lane for the whole run, so it never jogs mid-channel.
 */
function assignLanes(runsOf: Run[][], widthAt: Map<string, number>, spacing: number): number[][] {
  const byLine = new Map<string, { route: number; index: number; low: number; high: number }[]>();
  runsOf.forEach((routeRuns, route) => routeRuns.forEach((run, index) => {
    const key = `${run.vertical ? 'v' : 'h'}:${coord(run.c)}`;
    const entry = { route, index, low: Math.min(run.a, run.b), high: Math.max(run.a, run.b) };
    byLine.set(key, [...(byLine.get(key) ?? []), entry]);
  }));
  const offsets = runsOf.map(routeRuns => routeRuns.map(() => 0));
  for (const [key, entries] of byLine) {
    entries.sort((a, b) => a.low - b.low || a.route - b.route);
    const laneEnds: number[] = [];
    const laneOf = entries.map(entry => {
      let lane = laneEnds.findIndex(end => end < entry.low - EPS);
      if (lane < 0) { lane = laneEnds.length; laneEnds.push(entry.high); } else { laneEnds[lane] = entry.high; }
      return lane;
    });
    const lanes = laneEnds.length;
    if (lanes < 2) { continue; }
    const width = widthAt.get(key) ?? 0;
    const gap = width > 0 ? Math.min(spacing, (width * 0.8) / lanes) : spacing;
    entries.forEach((entry, i) => {
      offsets[entry.route][entry.index] = (laneOf[i] - (lanes - 1) / 2) * gap;
    });
  }
  return offsets;
}

/** The drawn line: corners where consecutive lanes meet, ends on the tables' borders. */
function polyline(routeRuns: Run[], offsets: number[], from: LayoutBox, to: LayoutBox): Point[] {
  const at = (run: Run, offset: number, along: number): Point =>
    run.vertical ? { x: run.c + offset, y: along } : { x: along, y: run.c + offset };
  const border = (run: Run, box: LayoutBox, leaving: boolean): number => {
    // the end of the run that touches the box, moved out to the box's edge
    const forward = run.b > run.a;
    if (run.vertical) {
      return leaving === forward ? box.y + box.h : box.y;
    }
    return leaving === forward ? box.x + box.w : box.x;
  };
  const points: Point[] = [];
  const first = routeRuns[0];
  points.push(at(first, offsets[0], border(first, from, true)));
  for (let i = 1; i < routeRuns.length; i++) {
    const before = routeRuns[i - 1];
    const after = routeRuns[i];
    // a vertical run at x + o meets a horizontal one at y + o'
    points.push(before.vertical
      ? { x: before.c + offsets[i - 1], y: after.c + offsets[i] }
      : { x: after.c + offsets[i], y: before.c + offsets[i - 1] });
  }
  const last = routeRuns[routeRuns.length - 1];
  points.push(at(last, offsets[offsets.length - 1], border(last, to, false)));
  return points;
}
