import {GraphV2} from '../schema-lens.service';
import {DEFAULT_LAYERS} from './graph-url';
import {neighbourhood} from './neighbourhood';

describe('neighbourhood', () => {

  const node = (schema: string, table: string) =>
    ({schema, table, columnCount: 1, queryCount: 1, warn: false, selfJoin: false, view: false});
  const join = (a: string, b: string) =>
    ({aSchema: 'app_main', aTable: a, bSchema: 'app_main', bTable: b, columns: [], queries: 1, executions: 1});

  // account — account_site — site — region, and invoice joined to nothing;
  // note is tied to account only by a foreign key
  const graph: GraphV2 = {
    nodes: ['account', 'account_site', 'site', 'region', 'invoice', 'note'].map(table => node('app_main', table)),
    joinEdges: [join('account', 'account_site'), join('account_site', 'site'), join('site', 'region')],
    fkEdges: [{fromSchema: 'app_main', fromTable: 'note', toSchema: 'app_main', toTable: 'account',
      label: 'account_id→id', mismatch: false}],
    coQueryEdges: [], viewEdges: []
  };
  const tables = (result: GraphV2) => result.nodes.map(each => each.table).sort();

  it('keeps the tables within the given number of hops', () => {
    expect(tables(neighbourhood(graph, 'account', 1, DEFAULT_LAYERS))).toEqual(['account', 'account_site']);
    expect(tables(neighbourhood(graph, 'account', 2, DEFAULT_LAYERS))).toEqual(['account', 'account_site', 'site']);
    expect(tables(neighbourhood(graph, 'Account', 9, DEFAULT_LAYERS)))
      .toEqual(['account', 'account_site', 'region', 'site']);
  });

  it('walks only the kinds of line that are shown', () => {
    // foreign keys are off by default, so note is not a neighbour…
    expect(tables(neighbourhood(graph, 'note', 1, DEFAULT_LAYERS))).toEqual(['note']);
    // …until they are on
    expect(tables(neighbourhood(graph, 'note', 1, {...DEFAULT_LAYERS, fk: true}))).toEqual(['account', 'note']);
  });

  it('starts from every schema holding the name', () => {
    const twoSchemas: GraphV2 = {...graph, nodes: [...graph.nodes, node('app_billing', 'account')]};
    expect(neighbourhood(twoSchemas, 'account', 1, DEFAULT_LAYERS).nodes.map(each => `${each.schema}.${each.table}`).sort())
      .toEqual(['app_billing.account', 'app_main.account', 'app_main.account_site']);
  });

  it('leaves the graph whole for a name on no table, or no depth', () => {
    expect(neighbourhood(graph, 'gone', 2, DEFAULT_LAYERS)).toBe(graph);
    expect(neighbourhood(graph, 'account', 0, DEFAULT_LAYERS)).toBe(graph);
  });
});
