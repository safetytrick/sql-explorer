package com.github.safetytrick.sqlexplorer.devidp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.ParseException;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The IdP's one signing key, kept as a private JWK in a file so it is
 * generated once rather than on every start. A key that changes on restart
 * invalidates every token and session the backend holds, which in development
 * means signing in again after each rebuild.
 */
final class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private SigningKeys() { }

    static RSAKey loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                JWK jwk = JWK.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (!(jwk instanceof RSAKey rsa) || !rsa.isPrivate()) {
                    throw new IllegalStateException(file + " does not hold a private RSA key; delete it to generate a new one");
                }
                log.info("Signing key {} loaded from {}", rsa.getKeyID(), file.toAbsolutePath());
                return rsa;
            }
            RSAKey rsa = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            // Write then move, so a crash mid-write never leaves a half key to fail the next start.
            Path tmp = Files.createTempFile(dir, "signing-key", ".tmp");
            Files.writeString(tmp, rsa.toJSONString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("Signing key {} generated and saved to {}", rsa.getKeyID(), file.toAbsolutePath());
            return rsa;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or write the signing key at " + file, e);
        } catch (ParseException | JOSEException e) {
            throw new IllegalStateException("Cannot load the signing key at " + file + "; delete it to generate a new one", e);
        }
    }
}
