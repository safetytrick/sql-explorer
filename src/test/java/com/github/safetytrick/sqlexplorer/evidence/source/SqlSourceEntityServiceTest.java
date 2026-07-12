package com.github.safetytrick.sqlexplorer.evidence.source;

import java.util.Optional;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import jakarta.persistence.EntityManager;
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
        service = new SqlSourceEntityService(sqlSourceEntityRepository, sourceRefEntityRepository,
            mock(UserService.class), mock(EntityManager.class));

        // dedup hit: the upload's content already exists, so no blob is written
        when(sqlSourceEntityRepository.findByMd5Hash(any())).thenReturn(Optional.of(new SqlSourceEntity()));
    }

    @Test
    void uploadAttributesTheSourceRefToTheEnvironment() {
        when(sourceRefEntityRepository.findById("my-ref")).thenReturn(Optional.empty());

        service.saveUploadedSqlSource("my-ref", "file:/dump.tsv", new ByteArrayResource(new byte[0]), "prod");

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

        service.saveUploadedSqlSource("my-ref", "file:/dump.tsv", new ByteArrayResource(new byte[0]), "prod");

        assertThat(savedSourceRef().getEnvironmentName()).isEqualTo("prod");
    }

    private SourceRefEntity savedSourceRef() {
        ArgumentCaptor<SourceRefEntity> captor = ArgumentCaptor.forClass(SourceRefEntity.class);
        verify(sourceRefEntityRepository).save(captor.capture());
        return captor.getValue();
    }
}
