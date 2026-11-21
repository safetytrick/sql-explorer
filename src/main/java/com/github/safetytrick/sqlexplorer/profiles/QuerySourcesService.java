package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.environment.Cluster;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.ServerRole;
import com.github.safetytrick.sqlexplorer.evidence.SourceScope;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryManager;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;

@Service
public class QuerySourcesService {

    private static final BigDecimal PICOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000_000L);

    private final SqlRepositoryManager repositories;
    private final EnvironmentContext environmentContext;

    public QuerySourcesService(SqlRepositoryManager repositories, EnvironmentContext environmentContext) {
        this.repositories = repositories;
        this.environmentContext = environmentContext;
    }

    public record QuerySource(String source, String cluster, List<String> roles, Instant capturedAt,
                              long executions, double seconds, double averageMillis, long rowsScanned,
                              long rowsSent, long rowsAffected, LocalDateTime firstSeen, LocalDateTime lastSeen,
                              double shareOfTime) { }

    public List<QuerySource> whereItRuns(String id) {
        Environment environment = environmentContext.current();
        String name = environment.getName();
        if (repositories.getSqlRepository(name).getByDigest(id) == null) {
            return null;
        }
        Set<String> refs = new LinkedHashSet<>();
        List<SqlRepository> loaded = repositories.getRepositories(name);
        loaded.forEach(repository -> {
            if (repository.sourceRef() != null) {
                refs.add(repository.sourceRef());
            }
        });

        List<QuerySource> sources = new ArrayList<>();
        double total = 0;
        for (String ref : refs) {
            SqlProfile profile = repositories.getSqlRepository(name, SourceScope.only(ref)).getByDigest(id);
            RuntimeMetrics metrics = profile == null ? null : profile.getRuntimeMetrics();
            if (metrics == null) {
                continue;
            }
            double seconds = seconds(metrics.getTotalTime());
            total += seconds;
            DatabaseServer server = serverNamed(environment, ref);
            sources.add(new QuerySource(ref, clusterOf(environment, server),
                server == null ? List.of() : server.getRoles().stream().map(ServerRole::configName).toList(),
                capturedAt(loaded, ref), metrics.getExecuted(), seconds,
                seconds(metrics.getAverageTime()) * 1000, metrics.getRowsScanned(), metrics.getRowsSent(),
                metrics.getRowsAffected(), metrics.getFirstSeen(), metrics.getLastSeen(), 0));
        }
        double all = total;
        return sources.stream()
            .map(s -> new QuerySource(s.source(), s.cluster(), s.roles(), s.capturedAt(), s.executions(),
                s.seconds(), s.averageMillis(), s.rowsScanned(), s.rowsSent(), s.rowsAffected(), s.firstSeen(),
                s.lastSeen(), all > 0 ? s.seconds() / all : 0))
            .sorted(Comparator.comparingDouble(QuerySource::seconds).reversed()
                .thenComparing(QuerySource::source))
            .toList();
    }

    private static double seconds(BigDecimal picos) {
        return picos == null ? 0 : picos.divide(PICOS_PER_SECOND, MathContext.DECIMAL64).doubleValue();
    }

    private static DatabaseServer serverNamed(Environment environment, String ref) {
        return environment.getServers().stream().filter(server -> server.getName().equals(ref)).findFirst()
            .orElse(null);
    }

    private static String clusterOf(Environment environment, DatabaseServer server) {
        if (server == null) {
            return null;
        }
        return environment.getClusters().stream().filter(cluster -> cluster.servers().contains(server))
            .map(Cluster::name).findFirst().orElse(null);
    }

    private static Instant capturedAt(List<SqlRepository> loaded, String ref) {
        for (SqlRepository repository : loaded) {
            if (ref.equals(repository.sourceRef())) {
                CaptureContext capture = repository.getCaptureContext();
                if (capture != null && capture.capturedAt() != null) {
                    return capture.capturedAt();
                }
                return repository.getSource() == null ? null : repository.getSource().getCreatedOn();
            }
        }
        return null;
    }
}
