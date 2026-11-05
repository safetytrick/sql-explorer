/**
 * A table's neighbourhood: the tables within some number of hops of it
 * (sqlx-aoy.9). The legacy table graph offered this as `/depth/:n`; it lives
 * here now, as `var-depth` on the selected table, so a busy environment can be
 * read one table's surroundings at a time.
 *
 * Pure: the graph lens applies it to what it fetched before laying it out, so
 * the grid compacts around what is left.
 */
import {GraphV2} from '../schema-lens.service';
import {GraphLayers} from './graph-url';

const key = (schema: string, table: string) => `${schema}.${table}`.toLowerCase();

/**
 * The graph cut down to the tables within `depth` hops of `table`, walking
 * only the kinds of line that are shown: a hop the reader cannot see would
 * pull in a table with no visible reason to be there.
 *
 * <p>`table` is a name without its schema, the way the selection is kept, so
 * a name in more than one schema starts from each. A name on no table leaves
 * the graph whole rather than empty: the selection may simply be stale.
 */
export function neighbourhood(graph: GraphV2, table: string, depth: number, layers: GraphLayers): GraphV2 {
  const name = table.toLowerCase();
  const seeds = graph.nodes.filter(node => node.table.toLowerCase() === name).map(node => key(node.schema, node.table));
  if (!seeds.length || depth < 1) { return graph; }

  const adjacent = new Map<string, Set<string>>();
  const link = (a: string, b: string) => {
    if (a === b) { return; }
    adjacent.set(a, (adjacent.get(a) ?? new Set()).add(b));
    adjacent.set(b, (adjacent.get(b) ?? new Set()).add(a));
  };
  if (layers.fk) {
    graph.fkEdges.forEach(edge => link(key(edge.fromSchema, edge.fromTable), key(edge.toSchema, edge.toTable)));
  }
  if (layers.join) {
    (graph.joinEdges ?? []).forEach(edge => link(key(edge.aSchema, edge.aTable), key(edge.bSchema, edge.bTable)));
  }
  if (layers.co) {
    graph.coQueryEdges.forEach(edge => link(key(edge.aSchema, edge.aTable), key(edge.bSchema, edge.bTable)));
  }
  if (layers.view) {
    graph.viewEdges.forEach(edge => link(key(edge.fromSchema, edge.fromTable), key(edge.toSchema, edge.toTable)));
  }

  const reached = new Set(seeds);
  let frontier = seeds;
  for (let hop = 0; hop < depth && frontier.length; hop++) {
    const next: string[] = [];
    for (const at of frontier) {
      for (const other of adjacent.get(at) ?? []) {
        if (!reached.has(other)) { reached.add(other); next.push(other); }
      }
    }
    frontier = next;
  }
  return { ...graph, nodes: graph.nodes.filter(node => reached.has(key(node.schema, node.table))) };
}
