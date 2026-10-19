package com.github.safetytrick.sqlexplorer.environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * Config for one cluster within an environment (under
 * {@code environments.<env>.clusters.<name>}): a primary and its replicas, which
 * carry the same schema.
 *
 * <pre>
 * clusters:
 *   shard1:
 *     schemaFrom: [shard1, shard1-replica-a]
 *     servers:
 *       shard1: { jdbcUrl: …, sources: [ { type: x-statement-analysis } ] }
 *       shard1-replica-a: …
 *       shard1-etl: …
 * </pre>
 *
 * <p>Every server is captured from; only the servers named in
 * {@code schemaFrom} are read for schema, first reachable wins. It is required
 * rather than defaulted because the obvious default — every server, in order —
 * would make an ETL instance a schema source, and declaration order is not
 * reliable once several config files contribute (docs/plans/cluster-schema-snapshots.md).
 */
@Getter
@Setter
public class ClusterDefinition {
    private List<String> schemaFrom = new ArrayList<>();
    private Map<String, ServerDefinition> servers = new LinkedHashMap<>();
}
