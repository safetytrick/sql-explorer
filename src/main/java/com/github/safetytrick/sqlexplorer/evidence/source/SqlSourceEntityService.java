package com.github.safetytrick.sqlexplorer.evidence.source;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.util.Base64;
import java.util.Optional;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import com.github.safetytrick.sqlexplorer.evidence.fileloader.AbstractTsvSqlSource;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.engine.jdbc.LobCreator;
import org.hibernate.engine.spi.SessionImplementor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class SqlSourceEntityService {
    private final SqlSourceEntityRepository sqlSourceEntityRepository;
    private final UserService userService;
    private final EntityManager entityManager;

    SqlSourceEntityService(SqlSourceEntityRepository sqlSourceEntityRepository,
                                  UserService userService,
                                  EntityManager entityManager) {
        this.sqlSourceEntityRepository = sqlSourceEntityRepository;
        this.userService = userService;
        this.entityManager = entityManager;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SqlSourceEntity saveSqlSource(AbstractTsvSqlSource sqlSource) {
        String sourceUrl;
        try {
            sourceUrl = sqlSource.getResource().getURL().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return saveUploadedSqlSource(sqlSource.getSourceRef(),
                sourceUrl,
                sqlSource.getResource());
    }

    public SqlSourceEntity saveUploadedSqlSource(String sourceRef,
                                                 String sourceUrl,
                                                 Resource resource) {
        try {
            String md5 = calculateMd5Hash(resource);
            Optional<SqlSourceEntity> existingEntity = sqlSourceEntityRepository.findByMd5Hash(md5);
            if (existingEntity.isPresent()) {
                return existingEntity.get();
            }
            SqlSourceEntity entity = new SqlSourceEntity();
            entity.setMd5Hash(md5);
            entity.setSourceRef(sourceRef);
            entity.setSourceUrl(sourceUrl);
            entity.setCreator(userService.currentUser());

            SessionImplementor hibernateSession = entityManager.unwrap(SessionImplementor.class);

            LobCreator lobCreator = hibernateSession.getLobCreator();
            Blob content = lobCreator.createBlob(resource.getInputStream(), resource.contentLength());

            entity.setContent(content);
            return sqlSourceEntityRepository.save(entity);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to access resource", e);
        }
    }

    private String calculateMd5Hash(Resource resource) throws IOException {
        try (BufferedInputStream stream = new BufferedInputStream(resource.getInputStream())) {
            try {
                int read;
                byte[] bytes = new byte[1024];
                MessageDigest digest = MessageDigest.getInstance("MD5");
                while ((read = stream.read(bytes)) != -1) {
                    digest.update(bytes, 0, read);
                }
                return Base64.getEncoder().encodeToString(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("MD5 algorithm not found", e);
            }
        }
    }
}
