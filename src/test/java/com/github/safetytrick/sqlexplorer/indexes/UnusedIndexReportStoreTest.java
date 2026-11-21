package com.github.safetytrick.sqlexplorer.indexes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.github.safetytrick.sqlexplorer.coverage.Coverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class UnusedIndexReportStoreTest {

    @TempDir
    Path directory;

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    private static UnusedIndexes.Report report(String environment) {
        return new UnusedIndexes.Report(environment,
            List.of(new UnusedIndexes.Connection("primary", Coverage.Verdict.OK, null,
                    UnusedIndexService.WITHOUT_SYS_NOTE, 30 * 24 * 3600L, 1, 4),
                new UnusedIndexes.Connection("replica", Coverage.Verdict.UNKNOWN, "connection refused", null, null, 0, 0)),
            false, List.of(new UnusedIndexes.Index("app_main", "account", "idx_old")), "basis",
            Instant.parse("2026-11-05T12:00:00Z"), 1234, 7);
    }

    @Test
    void writesAndReadsBackEverythingTheListingShows() {
        UnusedIndexReportStore store = new UnusedIndexReportStore(directory, json);
        store.write(report("prod"));

        UnusedIndexes.Report read = new UnusedIndexReportStore(directory, json).read("prod").orElseThrow();

        assertThat(read).isEqualTo(report("prod"));
        assertThat(store.read("staging")).isEmpty();
    }

    @Test
    void keepsAnEnvironmentNameThatIsNotAFileNameApart() {
        UnusedIndexReportStore store = new UnusedIndexReportStore(directory, json);
        store.write(report("a/../b"));
        assertThat(store.fileFor("a/../b").getParent()).isEqualTo(directory);
        assertThat(store.read("a/../b")).contains(report("a/../b"));
    }

    @Test
    void anUnreadableOrOlderFileIsNoReport() throws Exception {
        UnusedIndexReportStore store = new UnusedIndexReportStore(directory, json);
        Files.createDirectories(directory);
        Files.writeString(store.fileFor("prod"), "{not json");
        assertThat(store.read("prod")).isEmpty();

        Files.writeString(store.fileFor("prod"), "{\"format\": 0, \"report\": null}");
        assertThat(store.read("prod")).isEmpty();
    }

    @Test
    void writingWhereNothingCanBeWrittenLosesTheCopyNotTheRead() throws Exception {
        Path file = directory.resolve("not-a-directory");
        Files.writeString(file, "");
        UnusedIndexReportStore store = new UnusedIndexReportStore(file, json);
        store.write(report("prod"));
        assertThat(store.read("prod")).isEmpty();
    }
}
