package com.github.safetytrick.sqlexplorer.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PresumedKindTest {

    @ParameterizedTest(name = "{0} {1}({2}) is {3}")
    @CsvSource({
        "account_code, VARCHAR, 32, identifier",
        "external_id, VARCHAR, 64, identifier",
        "customerId, VARCHAR, 64, identifier",
        "email, VARCHAR, 255, identifier",
        "password_hash, CHAR, 60, identifier",
        "checksum, CHAR, 32, identifier",
        "query_digest, VARCHAR, 72, identifier",
        "status, VARCHAR, 20, enum",
        "invoice_type, VARCHAR, 32, enum",
        "currency, CHAR, 3, enum",
        "state, ENUM, 0, enum",
        "source_type, VARCHAR, 512, text",
        "name, VARCHAR, 255, text",
        "description, TEXT, 0, text",
        "paid, VARCHAR, 10, text",
        "monkey, VARCHAR, 10, text",
        "mid_point_note, VARCHAR, 100, text"
    })
    void presumesFromNameAndType(String name, String type, int size, String kind) {
        assertThat(PresumedKind.of(name, type, size).kind()).isEqualTo(kind);
    }
}
