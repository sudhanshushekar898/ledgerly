package dev.ledgerly.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokensTest {

    @Test
    void generatesDistinctUrlSafeTokens() {
        String a = Tokens.generate();
        assertThat(a).isNotEqualTo(Tokens.generate()).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void hashIsStableSha256HexAndNotTheToken() {
        String token = Tokens.generate();
        assertThat(Tokens.hash(token)).isEqualTo(Tokens.hash(token)).hasSize(64).isNotEqualTo(token);
    }
}
