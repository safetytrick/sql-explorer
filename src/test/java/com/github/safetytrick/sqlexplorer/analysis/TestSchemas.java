package com.github.safetytrick.sqlexplorer.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import net.sf.jsqlparser.JSQLParserException;

public final class TestSchemas {

    public static final String DEFAULT_SCHEMA = "tdb";
    private static final String RESOURCE_DIR = "schema";

    private TestSchemas() {
    }

    public static LoadedSchema of(String... tableNames) {
        List<TableStructure> structures = new ArrayList<>(tableNames.length);
        for (String tableName : tableNames) {
            structures.add(table(tableName));
        }
        return new LoadedSchema(structures);
    }

    public static LoadedSchema all() {
        List<TableStructure> structures = new ArrayList<>();
        for (String name : fixtureNames()) {
            structures.add(table(name));
        }
        return new LoadedSchema(structures);
    }

    public static TableStructure table(String tableName) {
        String create = read(tableName);
        try {
            return TableStructure.fromCreateTable(create, DEFAULT_SCHEMA).build();
        } catch (JSQLParserException e) {
            throw new IllegalStateException("could not parse fixture: " + tableName, e);
        }
    }

    private static String read(String tableName) {
        String resource = RESOURCE_DIR + "/" + tableName + ".sql";
        try (InputStream in = TestSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no schema fixture on the classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read fixture: " + resource, e);
        }
    }

    private static List<String> fixtureNames() {
        try {
            URI dir = TestSchemas.class.getClassLoader().getResource(RESOURCE_DIR).toURI();
            Path path = Paths.get(dir);
            try (Stream<Path> files = Files.list(path)) {
                return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .map(name -> name.substring(0, name.length() - ".sql".length()))
                    .sorted()
                    .toList();
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not enumerate schema fixtures", e);
        }
    }
}
