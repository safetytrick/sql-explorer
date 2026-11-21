package com.github.safetytrick.sqlexplorer.evidence.source;

import java.util.Optional;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import jakarta.persistence.EntityManager;
import org.hibernate.engine.jdbc.LobCreator;
import org.hibernate.engine.spi.SessionImplementor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlSourceEntityServiceTest {

    private SqlSourceEntityRepository sqlSourceEntityRepository;
    private SourceRefEntityRepository sourceRefEntityRepository;
    private SqlSourceEntityService service;

    @BeforeEach
    void setUp() {
        sqlSourceEntityRepository = mock(SqlSourceEntityRepository.class);
        sourceRefEntityRepository = mock(SourceRefEntityRepository.class);

        EntityManager entityManager = mock(EntityManager.class);
        SessionImplementor session = mock(SessionImplementor.class);
        LobCreator lobCreator = mock(LobCreator.class);
        when(entityManager.unwrap(SessionImplementor.class)).thenReturn(session);
        when(session.getLobCreator()).thenReturn(lobCreator);
        when(lobCreator.createBlob(any(java.io.InputStream.class), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(mock(java.sql.Blob.class));

        service = new SqlSourceEntityService(sqlSourceEntityRepository, sourceRefEntityRepository,
            mock(UserService.class), entityManager);

        when(sqlSourceEntityRepository.findByMd5Hash(any())).thenReturn(Optional.of(new SqlSourceEntity()));
    }

    private static final SqlSourceEntityService.CaptureDetails CAPTURE =
        new SqlSourceEntityService.CaptureDetails(
            java.time.Instant.parse("2026-08-29T14:03:35Z"), "8.4.4", 10000, 4096);

    @Test
    void uploadAttributesTheSourceRefToTheEnvironment() {
        when(sourceRefEntityRepository.findById("my-ref")).thenReturn(Optional.empty());

        service.saveUploadedSqlSource("my-ref", "file:/dump.tsv", new ByteArrayResource(new byte[0]), "prod",
            CAPTURE);

        SourceRefEntity saved = savedSourceRef();
        assertThat(saved.getName()).isEqualTo("my-ref");
        assertThat(saved.getEnvironmentName()).isEqualTo("prod");
    }

    @Test
    void reUploadingUnderAnotherEnvironmentMovesTheAttribution() {
        SourceRefEntity existing = new SourceRefEntity();
        existing.setName("my-ref");
        existing.setEnvironmentName("staging");
        when(sourceRefEntityRepository.findById("my-ref")).thenReturn(Optional.of(existing));

        service.saveUploadedSqlSource("my-ref", "file:/dump.tsv", new ByteArrayResource(new byte[0]), "prod",
            CAPTURE);

        assertThat(savedSourceRef().getEnvironmentName()).isEqualTo("prod");
    }


    @Test
    void anUploadRecordsWhatTheServerLookedLikeAtCaptureTime() {
        when(sqlSourceEntityRepository.findByMd5Hash(any())).thenReturn(Optional.empty());
        when(sqlSourceEntityRepository.save(any(SqlSourceEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(sourceRefEntityRepository.findById("my-ref")).thenReturn(Optional.empty());

        SqlSourceEntity saved = service.saveUploadedSqlSource(
            "my-ref", "file:/dump.tsv", new ByteArrayResource(new byte[0]), "prod", CAPTURE);

        assertThat(saved.getCapturedAt()).isEqualTo(java.time.Instant.parse("2026-08-29T14:03:35Z"));
        assertThat(saved.getServerVersion()).isEqualTo("8.4.4");
        assertThat(saved.getServerDigestsSize()).isEqualTo(10000);
        assertThat(saved.getServerMaxDigestLength()).isEqualTo(4096);
        assertThat(saved.getOrigin()).isEqualTo("CSV_EXPORT");
    }

    @Test
    void capturedAtIsSeparateFromWhenTheSourceWasCreated() {
        when(sqlSourceEntityRepository.findByMd5Hash(any())).thenReturn(Optional.empty());
        when(sqlSourceEntityRepository.save(any(SqlSourceEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(sourceRefEntityRepository.findById("my-ref")).thenReturn(Optional.empty());

        SqlSourceEntity saved = service.saveUploadedSqlSource(
            "my-ref", "file:/dump.tsv", new ByteArrayResource(new byte[0]), "prod", CAPTURE);

        assertThat(saved.getCapturedAt()).isNotEqualTo(saved.getCreatedOn());
    }

    @Test
    void anUnknownCaptureIsRecordedAsUnknownRatherThanGuessed() {
        when(sqlSourceEntityRepository.findByMd5Hash(any())).thenReturn(Optional.empty());
        when(sqlSourceEntityRepository.save(any(SqlSourceEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(sourceRefEntityRepository.findById("my-ref")).thenReturn(Optional.empty());

        SqlSourceEntity saved = service.saveUploadedSqlSource(
            "my-ref", "file:/dump.tsv", new ByteArrayResource(new byte[0]), "prod",
            new SqlSourceEntityService.CaptureDetails(null, null, null, null));

        assertThat(saved.getCapturedAt()).isNull();
        assertThat(saved.getServerDigestsSize()).isNull();
    }

    private SourceRefEntity savedSourceRef() {
        ArgumentCaptor<SourceRefEntity> captor = ArgumentCaptor.forClass(SourceRefEntity.class);
        verify(sourceRefEntityRepository).save(captor.capture());
        return captor.getValue();
    }
}