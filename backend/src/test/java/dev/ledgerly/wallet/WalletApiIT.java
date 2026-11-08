package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerly.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class WalletApiIT extends AbstractIntegrationTest {

    private String token;

    @BeforeEach
    void signUp() throws Exception {
        token = accessToken(register("priya@example.com", STRONG_PASSWORD));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void signUpOpensAnEmptyInrWallet() throws Exception {
        mvc.perform(get("/api/v1/wallet").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balance").value("0.00"));
    }

    @Test
    void topUpCreditsTheWalletAndReturnsAReceipt() throws Exception {
        MvcResult result = topUp(token, "topup-0001", "500");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("false");
        assertThat(body(result).get("amount").asText()).isEqualTo("500.00");
        assertThat(body(result).get("balanceAfter").asText()).isEqualTo("500.00");
        assertThat(body(result).get("transactionId").asText()).isNotBlank();
        mvc.perform(get("/api/v1/wallet").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.balance").value("500.00"));
    }

    @Test
    void retryWithTheSameKeyReplaysTheFirstReceiptAndMovesMoneyOnce() throws Exception {
        MvcResult first = topUp(token, "retry-key-01", "250.00");
        MvcResult retry = topUp(token, "retry-key-01", "250"); // same amount, written differently

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(retry)).isEqualTo(body(first));
        assertThat(jdbc.queryForObject("select count(*) from journal_transactions", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/wallet").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.balance").value("250.00"));
    }

    @Test
    void reusingAKeyForADifferentAmountIsRejected() throws Exception {
        topUp(token, "reused-key-1", "100.00");
        MvcResult reused = topUp(token, "reused-key-1", "999.00");

        assertThat(reused.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(reused).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        String other = accessToken(register("rahul@example.com", STRONG_PASSWORD));

        topUp(token, "shared-key-1", "10.00");
        MvcResult otherUsers = topUp(other, "shared-key-1", "20.00");

        assertThat(otherUsers.getResponse().getStatus()).isEqualTo(201);
        assertThat(otherUsers.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("false");
        assertThat(body(otherUsers).get("balanceAfter").asText()).isEqualTo("20.00");
    }

    @Test
    void missingOrMalformedKeyIsRejectedBeforeAnyWork() throws Exception {
        MvcResult missing = topUp(token, null, "10.00");
        MvcResult tooShort = topUp(token, "abc", "10.00");

        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(missing).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records", Integer.class)).isZero();
    }

    @Test
    void invalidAmountsAreRejectedAndDoNotConsumeTheKey() throws Exception {
        for (String bad : new String[] {"0", "-5", "10.555", "100000.01"}) {
            MvcResult result = topUp(token, "amount-key-1", bad);
            assertThat(result.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(body(result).get("code").asText()).isEqualTo("AMOUNT_INVALID");
        }
        assertThat(topUp(token, "amount-key-1", "10.00").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void nonNumericOrMissingAmountIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/wallet/topups").header("Authorization", bearer(token))
                        .header("Idempotency-Key", "bad-body-001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"ten\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/wallet/topups").header("Authorization", bearer(token))
                        .header("Idempotency-Key", "bad-body-002").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void statementShowsTopUpsNewestFirstWithPaging() throws Exception {
        topUp(token, "stmt-key-001", "100.00");
        topUp(token, "stmt-key-002", "50.00");
        topUp(token, "stmt-key-003", "25.00");

        MvcResult page = mvc.perform(get("/api/v1/wallet/statement?limit=2").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].amount").value("25.00"))
                .andExpect(jsonPath("$.items[0].balanceAfter").value("175.00"))
                .andExpect(jsonPath("$.items[0].kind").value("TOPUP"))
                .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.nextBefore").value(2))
                .andReturn();
        long next = body(page).get("nextBefore").asLong();
        mvc.perform(get("/api/v1/wallet/statement?limit=2&before=" + next).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].balanceAfter").value("100.00"))
                .andExpect(jsonPath("$.nextBefore").doesNotExist());
    }

    @Test
    void walletEndpointsRequireAUserToken() throws Exception {
        mvc.perform(get("/api/v1/wallet")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/wallet/topups").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/wallet").header("Authorization", bearer(adminToken())))
                .andExpect(status().isForbidden());
    }
}
