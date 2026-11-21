package com.github.safetytrick.sqlexplorer.profiling;

import java.time.Instant;

import com.github.safetytrick.sqlexplorer.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "profile_run")
public class ProfileRun {

    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String environmentName;
    private String schemaName;
    private String tableName;

    private String probeServer;

    private String method;

    @Column(name = "columns_list")
    private String columnsList;

    private String status;

    @ManyToOne
    private User requestedBy;

    private Instant startedOn;
    private Instant finishedOn;

    private long rowsRead;
    private int statements;

    private String planJson;

    private String resultJson;

    private String error;
}
