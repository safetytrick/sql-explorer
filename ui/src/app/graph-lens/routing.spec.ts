import {GridNode, GridSpacing, layoutGrid, LayoutBox} from './grid-layout';
import {assignLanes, Point, routeEdges, Run} from './routing';

const SIZE = { w: 130, h: 44 };
const leaf = (key: string, col: number, row: number): GridNode => ({ key, size: SIZE, slot: { col, row } });
const SPACING: GridSpacing[] = [
  { gap: 80, pad: 0, header: 0 },
  { gap: 30, pad: 30, header: 0 },
  { gap: 14, pad: 12, header: 18 }
];

function leaves(boxes: LayoutBox[]): LayoutBox[] {
  return boxes.flatMap(box => box.children.length ? leaves(box.children) : [box]);
}

/** Does the segment p→q pass through the inside of the box? */
function crosses(p: Point, q: Point, box: LayoutBox): boolean {
  const left = Math.min(p.x, q.x), right = Math.max(p.x, q.x);
  const top = Math.min(p.y, q.y), bottom = Math.max(p.y, q.y);
  return left < box.x + box.w - 0.5 && right > box.x + 0.5 && top < box.y + box.h - 0.5 && bottom > box.y + 0.5;
}

function expectOrthogonal(points: Point[]) {
  for (let i = 1; i < points.length; i++) {
    expect(points[i].x === points[i - 1].x || points[i].y === points[i - 1].y)
      .withContext(`segment ${i} of ${JSON.stringify(points)}`).toBeTrue();
  }
}

function expectClearOf(points: Point[], boxes: LayoutBox[], except: string[]) {
  for (const box of leaves(boxes).filter(candidate => !except.includes(candidate.key))) {
    for (let i = 1; i < points.length; i++) {
      expect(crosses(points[i - 1], points[i], box)).withContext(`through ${box.key}`).toBeFalse();
    }
  }
}

function onBorder(point: Point, box: LayoutBox): boolean {
  const withinX = point.x >= box.x - 0.01 && point.x <= box.x + box.w + 0.01;
  const withinY = point.y >= box.y - 0.01 && point.y <= box.y + box.h + 0.01;
  const onX = Math.abs(point.x - box.x) < 0.01 || Math.abs(point.x - box.x - box.w) < 0.01;
  const onY = Math.abs(point.y - box.y) < 0.01 || Math.abs(point.y - box.y - box.h) < 0.01;
  return withinX && withinY && (onX || onY);
}

describe('routeEdges', () => {

  const schema = (key: string, children: GridNode[], col = 0): GridNode => ({ key, slot: { col, row: 0 }, children });

  it('runs straight between neighbours in a row, border to border', () => {
    const boxes = layoutGrid([schema('s:a', [leaf('a', 0, 0), leaf('b', 1, 0)])], SPACING);
    const [a, b] = leaves(boxes);
    const route = routeEdges(boxes, [{ id: 'ab', from: 'a', to: 'b' }]).get('ab')!;
    expect(route.length).toBe(2);
    expect(route[0]).toEqual({ x: a.x + a.w, y: a.y + a.h / 2 });
    expect(route[1]).toEqual({ x: b.x, y: b.y + b.h / 2 });
  });

  it('goes around a table in the way rather than through it', () => {
    const boxes = layoutGrid([schema('s:a', [leaf('a', 0, 0), leaf('b', 1, 0), leaf('c', 2, 0)])], SPACING);
    const route = routeEdges(boxes, [{ id: 'ac', from: 'a', to: 'c' }]).get('ac')!;
    expectOrthogonal(route);
    expectClearOf(route, boxes, ['a', 'c']);
    expect(onBorder(route[0], leaves(boxes)[0])).toBeTrue();
    expect(onBorder(route[route.length - 1], leaves(boxes)[2])).toBeTrue();
  });

  it('climbs out of aggregates and across schemas without crossing a table', () => {
    const aggregate = (key: string, col: number, row: number, names: string[]): GridNode => ({
      key, slot: { col, row }, children: names.map((name, i) => leaf(name, i % 2, Math.floor(i / 2)))
    });
    const boxes = layoutGrid([
      schema('s:app_main', [
        aggregate('a:invoice', 0, 0, ['invoice', 'invoice_item', 'invoice_item_tax']),
        aggregate('a:account', 1, 0, ['account', 'account_site']),
        leaf('site', 0, 1), leaf('note', 1, 1)
      ]),
      schema('s:app_billing', [aggregate('a:payment', 0, 0, ['payment', 'payment_line', 'payment_refund'])], 1)
    ], SPACING);
    const requests = [
      { id: '1', from: 'invoice_item_tax', to: 'payment_refund' },
      { id: '2', from: 'account_site', to: 'site' },
      { id: '3', from: 'note', to: 'invoice' },
      { id: '4', from: 'payment_line', to: 'account' }
    ];
    const routes = routeEdges(boxes, requests);
    expect(routes.size).toBe(4);
    for (const request of requests) {
      const route = routes.get(request.id)!;
      expectOrthogonal(route);
      expectClearOf(route, boxes, [request.from, request.to]);
    }
  });

  it('bundles routes heading the same way into one channel, side by side in lanes', () => {
    // two tables on the left, two on the right, far enough apart that the
    // routes share the long stretch between them
    const boxes = layoutGrid([
      schema('s:left', [leaf('a1', 0, 0), leaf('a2', 0, 1)]),
      schema('s:right', [leaf('b1', 0, 0), leaf('b2', 0, 1)], 1)
    ], SPACING);
    const routes = routeEdges(boxes, [
      { id: '1', from: 'a1', to: 'b2' },
      { id: '2', from: 'a2', to: 'b1' }
    ]);
    const one = routes.get('1')!;
    const two = routes.get('2')!;
    // the long vertical stretches: in the same gutter, a lane apart
    const verticalXs = (route: Point[]) => route.slice(1)
      .filter((point, i) => point.x === route[i].x).map(point => point.x);
    const shared = verticalXs(one).filter(x => verticalXs(two).some(other => Math.abs(other - x) < 10));
    expect(shared.length).withContext(`${JSON.stringify(one)} / ${JSON.stringify(two)}`).toBeGreaterThan(0);
    expect(verticalXs(one)).not.toEqual(verticalXs(two));
  });

  // ── ports: an ERD line meets a column's row (sqlx-929) ──

  describe('ports', () => {
    const tall = (key: string, col: number, row: number): GridNode => ({ key, size: { w: 190, h: 120 }, slot: { col, row } });

    it('ends a route on a side edge at the port, never the top or bottom', () => {
      // b sits directly below a, where a centre-to-centre route would leave a's bottom edge
      const boxes = layoutGrid([schema('s:a', [tall('a', 0, 0), tall('b', 0, 1)])], SPACING);
      const [a, b] = leaves(boxes);
      const route = routeEdges(boxes, [{ id: 'ab', from: 'a', to: 'b', fromPort: a.y + 40, toPort: b.y + 72 }]).get('ab')!;
      expectOrthogonal(route);
      expectClearOf(route, boxes, []);
      const first = route[0];
      const last = route[route.length - 1];
      expect(first.y).toBe(a.y + 40);
      expect(last.y).toBe(b.y + 72);
      expect(first.x === a.x || first.x === a.x + a.w).toBeTrue();
      expect(last.x === b.x || last.x === b.x + b.w).toBeTrue();
    });

    it('brings every route into one port at the same point', () => {
      const boxes = layoutGrid([schema('s:a', [tall('hub', 1, 0), tall('a', 0, 0), tall('b', 0, 1), tall('c', 0, 2)])], SPACING);
      const hub = leaves(boxes).find(box => box.key === 'hub')!;
      const port = hub.y + 32;
      const routes = routeEdges(boxes, ['a', 'b', 'c'].map(from =>
        ({ id: from, from, to: 'hub', fromPort: leaves(boxes).find(box => box.key === from)!.y + 32, toPort: port })));
      expect(routes.size).toBe(3);
      for (const route of routes.values()) {
        expect(route[route.length - 1]).toEqual({ x: hub.x, y: port });
      }
    });

    it('keeps a box\'s centre lines for a route that names no port on it', () => {
      const boxes = layoutGrid([schema('s:a', [tall('a', 0, 0), tall('b', 1, 0), tall('c', 2, 0)])], SPACING);
      const [a, b] = leaves(boxes);
      const routes = routeEdges(boxes, [
        { id: 'port', from: 'a', to: 'b', fromPort: a.y + 20, toPort: b.y + 20 },
        { id: 'centre', from: 'b', to: 'c' }
      ]);
      expect(routes.get('port')![0].y).toBe(a.y + 20);
      expect(routes.get('centre')![0]).toEqual({ x: b.x + b.w, y: b.y + b.h / 2 });
    });
  });

  it('routes nothing for a missing table or a self-loop', () => {
    const boxes = layoutGrid([schema('s:a', [leaf('a', 0, 0)])], SPACING);
    const routes = routeEdges(boxes, [{ id: 'x', from: 'a', to: 'gone' }, { id: 'y', from: 'a', to: 'a' }]);
    expect(routes.size).toBe(0);
  });

  it('is deterministic: the same boxes and requests give the same routes', () => {
    const boxes = layoutGrid([schema('s:a', [leaf('a', 0, 0), leaf('b', 1, 1), leaf('c', 2, 0)])], SPACING);
    const requests = [{ id: 'ab', from: 'a', to: 'b' }, { id: 'bc', from: 'b', to: 'c' }];
    expect(routeEdges(boxes, requests)).toEqual(routeEdges(boxes, requests));
  });

  // the lanes of a channel go side by side by where their routes turn (sqlx-l4q)
  describe('lane order', () => {
    const vertical = (a: number, b: number): Run => ({ vertical: true, c: 100, a, b });
    const horizontal = (c: number, a: number, b: number): Run => ({ vertical: false, c, a, b });
    const widths = new Map([['v:100', 40]]);

    it('puts a route that turns west in the west lane, though it started later', () => {
      // both run down x = 100; the one that starts first turns east at the bottom
      const east = [vertical(-10, 100), horizontal(100, 100, 300)];
      const west = [vertical(0, 100), horizontal(140, 100, -100)];
      const offsets = assignLanes([east, west], widths, 4);
      expect(offsets[1][0]).toBeLessThan(offsets[0][0]);
    });

    it('counts where a route came from as well as where it goes', () => {
      // one comes in from the east and leaves east; the other passes through from the west
      const fromEast = [horizontal(-20, 300, 100), vertical(-20, 100), horizontal(100, 100, 300)];
      const fromWest = [horizontal(-40, -200, 100), vertical(-40, 120)];
      const offsets = assignLanes([fromEast, fromWest], widths, 4);
      expect(offsets[1][1]).toBeLessThan(offsets[0][1]);
    });

    it('spreads arrivals on a table\'s own centre line wider than lanes in a channel', () => {
      const one = [vertical(0, 100)];
      const two = [vertical(0, 100)];
      const spread = (lines: Set<string>) => {
        const offsets = assignLanes([one, two], new Map([['v:100', 130]]), 4, lines, 9);
        return Math.abs(offsets[0][0] - offsets[1][0]);
      };
      expect(spread(new Set())).toBe(4);
      expect(spread(new Set(['v:100']))).toBe(9);
    });
  });
});
