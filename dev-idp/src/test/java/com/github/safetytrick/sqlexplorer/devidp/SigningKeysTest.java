package com.github.safetytrick.sqlexplorer.devidp;

import java.nio.file.Files;
import java.nio.file.Path;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeysTest {

    @TempDir
    Path dir;

    @Test
    void generatesOnceThenReusesTheSameKey() {
        Path file = dir.resolve("nested/signing-key.json");
        RSAKey first = SigningKeys.loadOrCreate(file);
        assertThat(file).exists();
        RSAKey second = SigningKeys.loadOrCreate(file);
        assertThat(second.getKeyID()).isEqualTo(first.getKeyID());
        assertThat(second.isPrivate()).isTrue();
        assertThat(second.getModulus()).isEqualTo(first.getModulus());
    }

    @Test
    void refusesAFileThatIsNotAPrivateRsaKey() throws Exception {
        Path file = dir.resolve("signing-key.json");
        Files.writeString(file, SigningKeys.loadOrCreate(dir.resolve("other.json")).toPublicJWK().toJSONString());
        assertThatThrownBy(() -> SigningKeys.loadOrCreate(file)).hasMessageContaining("private RSA key");
    }
}
