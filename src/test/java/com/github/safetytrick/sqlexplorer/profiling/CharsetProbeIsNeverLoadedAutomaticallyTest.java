package com.github.safetytrick.sqlexplorer.profiling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class CharsetProbeIsNeverLoadedAutomaticallyTest {

    private static final String PROBE = "charset-probe";

    @Test
    void the_probe_exists_where_the_docs_say() {
        assertThat(Path.of("src/test/resources/profiling/charset-probe.sql")).isRegularFile();
    }

    @Test
    void nothing_that_runs_or_deploys_references_the_probe() throws IOException {
        List<Path> referencing = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main"), Path.of(".github"))) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile).filter(CharsetProbeIsNeverLoadedAutomaticallyTest::mentionsProbe)
                            .forEach(referencing::add);
                }
            }
        }
        try (Stream<Path> top = Files.list(Path.of("."))) {
            top.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("docker-compose") || name.endsWith(".sh") || name.startsWith("Dockerfile");
                    })
                    .filter(CharsetProbeIsNeverLoadedAutomaticallyTest::mentionsProbe)
                    .forEach(referencing::add);
        }
        assertThat(referencing).isEmpty();
    }

    private static boolean mentionsProbe(Path file) {
        try {
            return Files.readString(file).contains(PROBE);
        } catch (MalformedInputException binary) {
            return false;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
