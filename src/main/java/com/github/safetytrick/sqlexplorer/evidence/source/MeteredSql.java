package com.github.safetytrick.sqlexplorer.evidence.source;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MeteredSql {

    @JsonProperty("query")
    private String query;

    @JsonProperty("db")
    private String schema;

    @JsonProperty("full_scan")
    private String fullTableScan;

    @JsonProperty("exec_count")
    private long executed;
    @JsonProperty("err_count")
    private long errors;
    @JsonProperty("warn_count")
    private long warnings;

    // all time values are in picoseconds
    @JsonProperty("total_latency")
    private BigDecimal totalTime;
    @JsonProperty("max_latency")
    private BigDecimal maxTime;
    @JsonProperty("avg_latency")
    private BigDecimal averageTime;

    @JsonProperty("lock_latency")
    private BigDecimal lockTime;

    @JsonProperty("rows_sent")
    private long rowsSent;
    @JsonProperty("rows_sent_avg")
    private long averageRowsSent;

    @JsonProperty("rows_affected")
    private long rowsAffected;

    @JsonProperty("rows_affected_avg")
    private long averageRowsAffected;

    @JsonProperty("rows_examined")
    private long rowsScanned;
    @JsonProperty("rows_examined_avg")
    private long averageRowsScanned;

    @JsonProperty("tmp_tables")
    private long tempTables;
    @JsonProperty("tmp_disk_tables")
    private long tempDiskTables;

    @JsonProperty("rows_sorted")
    private long rowsSorted;
    @JsonProperty("sort_merge_passes")
    private long sortMergePasses;

    @JsonProperty("digest")
    private String digest;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonProperty("first_seen")
    private LocalDateTime firstSeen;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonProperty("last_seen")
    private LocalDateTime lastSeen;

}
