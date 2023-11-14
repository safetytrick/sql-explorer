package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.github.safetytrick.sqlexplorer.core.ColumnReference;
import com.github.safetytrick.sqlexplorer.service.SqlRepositoryService;

@Component
public class SqlMetadataAnalysisService {

    private final SqlRepositoryService sqlRepositoryService;

    public SqlMetadataAnalysisService(SqlRepositoryService sqlRepositoryService) {
        this.sqlRepositoryService = sqlRepositoryService;
    }


    public Set<List<String>> getAllJoinPairs(int threshold) {
        return sqlRepositoryService.getAllAnalyzedSql()
            .filter(query -> query.getRuntimeMetrics() == null || query.getRuntimeMetrics().getErrors() != query.getRuntimeMetrics().getExecuted())
            .filter(query -> query.getRuntimeMetrics() == null || query.getRuntimeMetrics().getExecuted() > threshold)
            .flatMap(sql -> sql.getJoinColumns().stream().map(
                joinPairs -> Stream.of(joinPairs.getLeft(), joinPairs.getRight()).map(ColumnReference::getNormalizedReference).sorted().collect(Collectors.toList())
            )).collect(this::stringComparableTreeSet, Set::add, Set::addAll);
    }

    private TreeSet<List<String>> stringComparableTreeSet() {
        return new TreeSet<>((first, second) -> {
            int response = first.size() - second.size();
            if (response != 0) {
                return response;
            }

            for (int i = 0; i < first.size(); i++) {
                String one = first.get(i);
                String two = second.get(i);
                response = one.compareTo(two);
                if (response != 0) {
                    return response;
                }
            }

            return response;
        });
    }
}
