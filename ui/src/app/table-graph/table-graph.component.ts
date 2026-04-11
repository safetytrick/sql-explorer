import {Component, Input} from '@angular/core';
import { ClusterNode, Edge, Layout, Node, GraphModule } from '@swimlane/ngx-graph';
import {Observable} from 'rxjs';

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
  set query(query: Observable<any>) {
    const groups = new GroupTracker();

    query.subscribe({
      next: tableJoins => {
        const tableJoinsArray = tableJoins as any[];
        tableJoinsArray.forEach(tableJoin => {
          groups.addTableJoins(tableJoin);
          tableJoin.foreignKeyTables.forEach((relatedTable: string) => {
            groups.addTable(relatedTable);
            this.createLink(tableJoin.table, relatedTable, 'fk');
          });

          tableJoin.joinTables.forEach((relatedTable: string) => {
            groups.addTable(relatedTable);
            this.createLink(tableJoin.table, relatedTable, 'joins');
          });

          tableJoin.relatedTables.forEach((relatedTable: string) => {
            groups.addTable(relatedTable);
            this.createLink(tableJoin.table, relatedTable, 'queried with');
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

  addTableJoins(tableJoins: any) {
    if (tableJoins.hasOwnProperty('group')) {
      this.tableGroups.set(tableJoins.table, tableJoins.group);
    }
    this.tables.add(tableJoins.table);
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
