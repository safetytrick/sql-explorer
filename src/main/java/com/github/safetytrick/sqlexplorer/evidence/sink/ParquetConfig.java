package com.github.safetytrick.sqlexplorer.evidence.sink;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("parquet-sink")
public class ParquetConfig {

    /**
     * The base path for written captures.
     *
     * <p>Not the file that gets written any more. Each capture writes a
     * sibling stamped with its capture instant — {@code
     * sql-repository-20260830T025136Z.parquet} beside a configured {@code
     * sql-repository.parquet} — so a capture no longer destroys the one before
     * it. See {@link ParquetSink#fileFor}.
     */
    private String filename;
}
