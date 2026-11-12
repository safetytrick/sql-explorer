package com.github.safetytrick.sqlexplorer.profiling;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

interface ProfileRunRepository extends CrudRepository<ProfileRun, Long> {

    List<ProfileRun> findAllByEnvironmentNameAndSchemaNameAndTableNameOrderByIdDesc(String environmentName,
                                                                                    String schemaName,
                                                                                    String tableName);

    List<ProfileRun> findAllByStatus(String status);
}
