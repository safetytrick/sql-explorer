import {Component, Input} from '@angular/core';
import { ClusterNode, Edge, Layout, Node, GraphModule } from '@swimlane/ngx-graph';
import {Observable} from 'rxjs';
import { GroupedTableRelationships, TableReference, TableRelationships } from '../api-types';

/** Node id for a table reference: normalized "schema.table" (or bare table when unscoped). */
function refKey(ref: TableReference): string {
  const schema = ref.normalizedSchemaName ?? ref.schemaName;
  return schema ? `${schema}.${ref.normalizedTableName}` : ref.normalizedTableName;
}

@Component({
    selector: 'app-table-graph',
    templateUrl: './table-graph.component.html',
    styleUrls: ['./table-graph.component.scss'],
    imports: [GraphModule]
})
export class TableGraphComponent {
  dataReceived = false;

  nodes: Node[] = [];
  links: Edge[] = [];
  clusters: ClusterNode[] = [];
  layout: String | Layout = 'dagreCluster';

  @Input()
  set query(query: Observable<TableRelationships[]>) {
    const groups = new GroupTracker();

    query.subscribe({
      next: tableJoins => {
        tableJoins.forEach(tableJoin => {
          groups.addTableJoins(tableJoin);
          const source = refKey(tableJoin.table);

          tableJoin.foreignKeyTables.forEach(relatedTable => {
            const target = refKey(relatedTable);
            groups.addTable(target);
            this.createLink(source, target, 'fk');
          });

          tableJoin.joinTables.forEach(relatedTable => {
            const target = refKey(relatedTable);
            groups.addTable(target);
            this.createLink(source, target, 'joins');
          });

          tableJoin.relatedTables.forEach(relatedTable => {
            const target = refKey(relatedTable);
            groups.addTable(target);
            this.createLink(source, target, 'queried with');
          });
        });

        this.createClusters(groups);
      },
      complete: () => this.dataReceived = true
    });
  }

  constructor() {
  }

  private createLink(sourceTable: string, targetTable: string, label: string) {
    const link: Edge = {
      id: sourceTable + '-' + targetTable,
      source: sourceTable,
      target: targetTable,
      label: label
    };

    this.links.push(link);
  }

  private createClusters(groups: GroupTracker) {
    const clusterNodeMap: Map<string, ClusterNode> = new Map<string, ClusterNode>();

    groups.getGroupedTables().forEach(pair => {
      const node: Node = {id: pair.table, label: pair.table};
      this.nodes.push(node);


      let clusterNode = clusterNodeMap.get(pair.group);
      if (!clusterNode) {
        clusterNode = {id: pair.group, label: pair.group, childNodeIds: []};
        clusterNodeMap.set(pair.group, clusterNode);
      }
      clusterNode.childNodeIds!.push(pair.table);
    });

    clusterNodeMap.forEach((value: ClusterNode, key: string) => {
      this.clusters.push(value);
    });
  }
}

class GroupTracker {
  private tableGroups = new Map<string, string>();
  private tables = new Set<string>();

  addTableJoins(tableJoins: TableRelationships) {
    const key = refKey(tableJoins.table);
    const group = (tableJoins as Partial<GroupedTableRelationships>).group;
    if (group) {
      this.tableGroups.set(key, group);
    }
    this.tables.add(key);
  }

  addTable(table: string) {
    this.tables.add(table);
  }

  getGroupedTables() {
    const grouped: {table: string, group: string}[] = [];
    this.tables.forEach(table => {
      if (this.tableGroups.has(table)) {
        grouped.push({table: table, group: this.tableGroups.get(table)!});
      } else if (table.indexOf('.') !== -1) {
        const schema = table.split('.')[0];
        grouped.push({table: table, group: schema});
      } else {
        grouped.push({table: table, group: 'no-group'});
      }
    });
    return grouped;
  }
}
