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

/**
 * One profile run, kept for good (sqlx-n5j.3): who asked, when, on which
 * servers, with which plan, the statements issued, the rows read and what was
 * found. A run is written when it starts and once more when it ends; nothing
 * deletes or replaces it, so every sample ever taken stays on record.
 */
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

    /** The one server a probe addressed; null when every cluster was sampled. */
    private String probeServer;

    /** {@link ProfilePlan.Method}, by name. */
    private String method;

    /** The profiled columns, comma separated. */
    @Column(name = "columns_list")
    private String columnsList;

    private String status;

    @ManyToOne
    private User requestedBy;

    private Instant startedOn;
    private Instant finishedOn;

    private long rowsRead;
    private int statements;

    /** {@link ProfileRequestPlan} as JSON: what was shown before the run, and agreed to. */
    private String planJson;

    /** {@link ProfileResult} as JSON. */
    private String resultJson;

    private String error;
}
