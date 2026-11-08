package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerly.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class TransferApiIT extends AbstractIntegrationTest {

    private static final String ASHA = "asha@example.com";
    private static final String RAVI = "ravi@example.com";

    private String asha;
    private String ravi;

    @BeforeEach
    void twoFundedCustomers() throws Exception {
        asha = accessToken(register(ASHA, STRONG_PASSWORD));
        ravi = accessToken(register(RAVI, STRONG_PASSWORD));
        topUp(asha, "fund-asha-01", "1000.00");
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String balance(String token) throws Exception {
        return body(mvc.perform(get("/api/v1/wallet").header("Authorization", bearer(token))).andReturn())
                .get("balance").asText();
    }

    private void expectError(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertThat(body(r).get("code").asText()).isEqualTo(code);
    }

    @Test
    void transferMovesMoneyAndBothStatementsShowTheOtherParty() throws Exception {
        MvcResult result = transfer(asha, "send-ravi-01", "RAVI@example.com", "250.50", "Dinner");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).get("recipientName").asText()).isEqualTo("Test User");
        assertThat(body(result).get("balanceAfter").asText()).isEqualTo("749.50");
        assertThat(balance(asha)).isEqualTo("749.50");
        assertThat(balance(ravi)).isEqualTo("250.50");

        mvc.perform(get("/api/v1/wallet/statement").header("Authorization", bearer(ravi)))
                .andExpect(jsonPath("$.items[0].kind").value("TRANSFER"))
                .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.items[0].memo").value("Dinner"))
                .andExpect(jsonPath("$.items[0].counterpartyName").value("Test User"));
        mvc.perform(get("/api/v1/wallet/statement").header("Authorization", bearer(asha)))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[1].kind").value("TOPUP"))
                .andExpect(jsonPath("$.items[1].counterpartyName").doesNotExist());
    }

    @Test
    void retryReplaysAndDifferentBodyWithSameKeyIsRejected() throws Exception {
        MvcResult first = transfer(asha, "send-ravi-02", RAVI, "100", null);
        MvcResult retry = transfer(asha, "send-ravi-02", RAVI, "100.00", null);
        MvcResult changed = transfer(asha, "send-ravi-02", RAVI, "100.00", "different note");

        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(retry)).isEqualTo(body(first));
        expectError(changed, 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(ravi)).isEqualTo("100.00");
    }

    @Test
    void insufficientFundsMovesNothing() throws Exception {
        expectError(transfer(asha, "too-much-001", RAVI, "1000.01", null), 422, "INSUFFICIENT_FUNDS");
        assertThat(balance(asha)).isEqualTo("1000.00");
        assertThat(balance(ravi)).isEqualTo("0.00");
        // a failed request leaves no idempotency record, so the same key can be used once funds arrive
        topUp(asha, "fund-asha-02", "1.00");
        assertThat(transfer(asha, "too-much-001", RAVI, "1000.01", null).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void selfTransferUnknownRecipientAndOperatorAreRejected() throws Exception {
        expectError(transfer(asha, "self-send-01", ASHA, "1.00", null), 422, "SAME_ACCOUNT");
        expectError(transfer(asha, "nobody-0001", "nobody@example.com", "1.00", null), 404, "RECIPIENT_NOT_FOUND");
        adminToken();
        expectError(transfer(asha, "to-admin-01", "operator@example.com", "1.00", null), 404, "RECIPIENT_NOT_FOUND");
    }

    @Test
    void perTransferMaximumAndBadInputAreRejected() throws Exception {
        expectError(transfer(asha, "max-check-1", RAVI, "25000.01", null), 400, "AMOUNT_INVALID");
        expectError(transfer(asha, "max-check-2", RAVI, "0", null), 400, "AMOUNT_INVALID");
        expectError(transfer(asha, "bad-email-1", "not-an-email", "1.00", null), 400, "VALIDATION_FAILED");
        expectError(transfer(asha, "bad-memo-01", RAVI, "1.00", "line\nbreak"), 400, "VALIDATION_FAILED");
        expectError(transfer(asha, "long-memo-1", RAVI, "1.00", "x".repeat(141)), 400, "VALIDATION_FAILED");
    }

    @Test
    void dailyLimitAllowsExactlyFiftyThousandThenBlocks() throws Exception {
        topUp(asha, "fund-asha-big", "60000.00");

        assertThat(transfer(asha, "daily-0001", RAVI, "25000.00", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(transfer(asha, "daily-0002", RAVI, "24999.99", null).getResponse().getStatus()).isEqualTo(201);
        MvcResult over = transfer(asha, "daily-0003", RAVI, "0.02", null);
        expectError(over, 422, "DAILY_LIMIT_EXCEEDED");
        assertThat(body(over).get("detail").asText()).contains("You can still send 0.01 today");
        assertThat(transfer(asha, "daily-0004", RAVI, "0.01", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(balance(ravi)).isEqualTo("50000.00");
    }

    @Test
    void frozenWalletCanNeitherSendNorReceiveNorTopUp() throws Exception {
        String admin = adminToken();
        String ashaWallet = body(mvc.perform(get("/api/v1/admin/wallets?email=" + ASHA)
                .header("Authorization", bearer(admin))).andReturn()).get("accountId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/admin/wallets/" + ashaWallet + "/freeze").header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"reason\":\"Suspected account takeover\"}"));

        expectError(transfer(asha, "frozen-send", RAVI, "1.00", null), 423, "WALLET_FROZEN");
        topUp(ravi, "fund-ravi-01", "10.00");
        expectError(transfer(ravi, "to-frozen-1", ASHA, "1.00", null), 423, "WALLET_FROZEN");
        expectError(topUp(asha, "frozen-topup", "1.00"), 423, "WALLET_FROZEN");
        mvc.perform(get("/api/v1/wallet").header("Authorization", bearer(asha)))
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.balance").value("1000.00"));
    }

    @Test
    void transfersRequireACustomerToken() throws Exception {
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/transfers")
                .contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(transfer(adminToken(), "admin-send", RAVI, "1.00", null).getResponse().getStatus()).isEqualTo(403);
    }
}
