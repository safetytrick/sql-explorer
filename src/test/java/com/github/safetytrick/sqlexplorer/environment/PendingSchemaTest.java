package com.github.safetytrick.sqlexplorer.environment;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.SchemaSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingSchemaTest {

    private static final SchemaSnapshot.Key KEY = new SchemaSnapshot.Key("production", "shard1");
    private static final int HANG_MILLIS = 3000;
    private static final long PROMPT_MILLIS = 500;

    private ServerSocket silent;
    private final List<Socket> accepted = new ArrayList<>();
    private SchemaRepositoryFactory factory;
    private DatabaseServer server;
    private LoadedSchema snapshot;

    @BeforeEach
    void silentServer() throws IOException {
        silent = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try {
                while (!silent.isClosed()) {
                    accepted.add(silent.accept());
                }
            } catch (IOException ignored) {
            }
        });
        factory = mock(SchemaRepositoryFactory.class);
        snapshot = mock(LoadedSchema.class);
        when(factory.lastKnown(KEY)).thenReturn(Optional.of(new SchemaRepositoryFactory.SchemaLoad(snapshot, null,
            true, false, Instant.parse("2026-11-05T12:00:00Z"), "shard1")));
        server = new DatabaseServer("shard1",
            new ConnectionInfo("jdbc:mysql://127.0.0.1:" + silent.getLocalPort()
                + "/?connectTimeout=" + HANG_MILLIS + "&socketTimeout=" + HANG_MILLIS, "reader", "pw"),
            List.of(), factory, KEY);
    }

    @AfterEach
    void close() throws IOException {
        server.close();
        silent.close();
        for (Socket socket : accepted) {
            socket.close();
        }
    }

    private CompletableFuture<Void> pullStuckInConnect() throws InterruptedException {
        CompletableFuture<Void> pull = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                server.getSchemaLoad();
                pull.complete(null);
            } catch (RuntimeException e) {
                pull.complete(null);
            }
        }, "pull");
        server.setPuller(thread);
        thread.start();
        Thread.sleep(400);
        assertThat(pull).withFailMessage("the silent server should be holding the pull").isNotDone();
        return pull;
    }

    private static long millisOf(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    @Test
    void whileThePullWaitsOnAConnectTheEnvironmentListAndTheSnapshotAnswerAtOnce() throws Exception {
        CompletableFuture<Void> pull = pullStuckInConnect();

        assertThat(millisOf(() -> assertThat(server.isSchemaLoaded()).isFalse())).isLessThan(PROMPT_MILLIS);
        assertThat(millisOf(() -> assertThatThrownBy(server::getSchemaLoad)
            .isInstanceOf(SchemaPendingException.class))).isLessThan(PROMPT_MILLIS);
        assertThat(millisOf(() -> assertThat(server.lastKnownSchema()).isPresent())).isLessThan(PROMPT_MILLIS);

        Cluster cluster = Cluster.of(server);
        long took = millisOf(() -> {
            Cluster.SchemaSource source = cluster.readSchema();
            assertThat(source.schema()).isSameAs(snapshot);
            assertThat(source.fromSnapshot()).isTrue();
            assertThat(source.current()).isFalse();
        });
        assertThat(took).isLessThan(PROMPT_MILLIS);

        pull.get(HANG_MILLIS * 3L, TimeUnit.MILLISECONDS);
        server.setPuller(null);
        assertThat(millisOf(() -> assertThatThrownBy(server::getSchemaLoad)
            .isNotInstanceOf(SchemaPendingException.class))).isLessThan(PROMPT_MILLIS);
    }

    @Test
    void whileTheEnvironmentIsPulledNoOtherCallerConnects() {
        server.setPuller(new Thread(() -> { }, "pull"));

        assertThatThrownBy(server::getSchemaLoad).isInstanceOf(SchemaPendingException.class);
        verify(factory, never()).loadCurrent(any(), any(), any());
    }

    @Test
    void outsideAPullASecondCallerDoesNotWaitOnTheFirst() throws Exception {
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                server.getSchemaLoad();
            } catch (RuntimeException ignored) {
            }
        });
        Thread.sleep(400);
        assertThat(first).isNotDone();

        assertThat(millisOf(() -> assertThatThrownBy(server::getSchemaLoad)
            .isInstanceOf(SchemaPendingException.class))).isLessThan(PROMPT_MILLIS);
        first.get(HANG_MILLIS * 3L, TimeUnit.MILLISECONDS);
    }
}
