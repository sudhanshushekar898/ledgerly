package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerly.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Operator flows: reconciliation, wallet lookup, freeze/unfreeze with audit, and reversals. */
class AdminIT extends AbstractIntegrationTest {

    private static final String ASHA = "asha@example.com";
    private static final String RAVI = "ravi@example.com";

    private String admin;
    private String asha;
    private String ravi;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken();
        asha = accessToken(register(ASHA, STRONG_PASSWORD));
        ravi = accessToken(register(RAVI, STRONG_PASSWORD));
        topUp(asha, "fund-asha-01", "1000.00");
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult adminPost(String path, String reason) throws Exception {
        return mvc.perform(post(path).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(reason == null ? "{}" : json.writeValueAsString(java.util.Map.of("reason", reason)))).andReturn();
    }

    private String walletId(String email) throws Exception {
        return body(mvc.perform(get("/api/v1/admin/wallets?email=" + email).header("Authorization", bearer(admin)))
                .andReturn()).get("accountId").asText();
    }

    private String balance(String token) throws Exception {
        return body(mvc.perform(get("/api/v1/wallet").header("Authorization", bearer(token))).andReturn())
                .get("balance").asText();
    }

    private String sendRavi(String key, String amount) throws Exception {
        MvcResult r = transfer(asha, key, RAVI, amount, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return body(r).get("transactionId").asText();
    }

    private void expectError(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertThat(body(r).get("code").asText()).isEqualTo(code);
    }

    @Test
    void reconciliationIsHealthyAfterActivityAndAdminOnly() throws Exception {
        sendRavi("send-0001", "300.00");

        mvc.perform(get("/api/v1/admin/reconciliation").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.totalOfAllBalances").value("0.00"))
                .andExpect(jsonPath("$.accountsChecked").value(10)) // 2 wallets + 8 top-up source shards (ADR-014)
                .andExpect(jsonPath("$.mismatchedAccounts").isEmpty());
        assertThat(mvc.perform(get("/api/v1/admin/reconciliation").header("Authorization", bearer(asha)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void findsWalletByEmail() throws Exception {
        mvc.perform(get("/api/v1/admin/wallets?email=ASHA@example.com").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.balance").value("1000.00"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        expectError(mvc.perform(get("/api/v1/admin/wallets?email=nobody@example.com")
                .header("Authorization", bearer(admin))).andReturn(), 404, "WALLET_NOT_FOUND");
    }

    @Test
    void freezeAndUnfreezeAreAuditedAndIdempotent() throws Exception {
        String wallet = walletId(ASHA);

        assertThat(body(adminPost("/api/v1/admin/wallets/" + wallet + "/freeze", "Chargeback investigation"))
                .get("status").asText()).isEqualTo("FROZEN");
        adminPost("/api/v1/admin/wallets/" + wallet + "/freeze", "Clicked twice"); // already frozen: no new audit row
        expectError(transfer(asha, "while-frozen", RAVI, "1.00", null), 423, "WALLET_FROZEN");

        assertThat(body(adminPost("/api/v1/admin/wallets/" + wallet + "/unfreeze", "Investigation closed"))
                .get("status").asText()).isEqualTo("ACTIVE");
        assertThat(transfer(asha, "after-unfreeze", RAVI, "1.00", null).getResponse().getStatus()).isEqualTo(201);

        assertThat(jdbc.queryForList("select status from account_status_changes where account_id = ?::uuid "
                + "order by created_at", String.class, wallet)).containsExactly("FROZEN", "ACTIVE");
        assertThat(jdbc.queryForObject("select count(distinct changed_by) from account_status_changes", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void freezeNeedsAReasonAndAnExistingWallet() throws Exception {
        expectError(adminPost("/api/v1/admin/wallets/" + walletId(ASHA) + "/freeze", null), 400, "VALIDATION_FAILED");
        expectError(adminPost("/api/v1/admin/wallets/" + UUID.randomUUID() + "/freeze", "x"), 404, "WALLET_NOT_FOUND");
        String systemAccount = "00000000-0000-7000-8000-000000000001";
        expectError(adminPost("/api/v1/admin/wallets/" + systemAccount + "/freeze", "x"), 404, "WALLET_NOT_FOUND");
    }

    @Test
    void reversalUndoesATransferExactlyOnce() throws Exception {
        String txn = sendRavi("send-0002", "400.00");

        MvcResult reversal = adminPost("/api/v1/admin/transactions/" + txn + "/reversal", "Sent to wrong person");
        assertThat(reversal.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(reversal).get("reversedTransactionId").asText()).isEqualTo(txn);
        assertThat(balance(asha)).isEqualTo("1000.00");
        assertThat(balance(ravi)).isEqualTo("0.00");
        mvc.perform(get("/api/v1/wallet/statement").header("Authorization", bearer(ravi)))
                .andExpect(jsonPath("$.items[0].kind").value("REVERSAL"))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].memo").value("Sent to wrong person"));

        expectError(adminPost("/api/v1/admin/transactions/" + txn + "/reversal", "again"), 409, "ALREADY_REVERSED");
        String reversalId = body(reversal).get("reversalId").asText();
        expectError(adminPost("/api/v1/admin/transactions/" + reversalId + "/reversal", "undo the undo"), 422, "NOT_REVERSIBLE");
        expectError(adminPost("/api/v1/admin/transactions/" + UUID.randomUUID() + "/reversal", "?"), 404, "TRANSACTION_NOT_FOUND");
        assertThat(body(mvc.perform(get("/api/v1/admin/reconciliation").header("Authorization", bearer(admin)))
                .andReturn()).get("healthy").asBoolean()).isTrue();
    }

    @Test
    void reversalFailsCleanlyIfTheRecipientAlreadySpentTheMoney() throws Exception {
        String txn = sendRavi("send-0003", "500.00");
        accessToken(register("meera@example.com", STRONG_PASSWORD));
        assertThat(transfer(ravi, "ravi-spends", "meera@example.com", "450.00", null).getResponse().getStatus())
                .isEqualTo(201);

        expectError(adminPost("/api/v1/admin/transactions/" + txn + "/reversal", "Fraud"), 422, "INSUFFICIENT_FUNDS");
        assertThat(balance(ravi)).isEqualTo("50.00");
        assertThat(jdbc.queryForObject("select count(*) from journal_transactions where kind = 'REVERSAL'", Integer.class))
                .isZero();
    }

    @Test
    void reversalStillWorksOnAFrozenWalletToClawBackFraud() throws Exception {
        String txn = sendRavi("send-0004", "200.00");
        adminPost("/api/v1/admin/wallets/" + walletId(RAVI) + "/freeze", "Fraud ring");

        assertThat(adminPost("/api/v1/admin/transactions/" + txn + "/reversal", "Clawback").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(balance(asha)).isEqualTo("1000.00");
        assertThat(balance(ravi)).isEqualTo("0.00");
    }

    @Test
    void topUpCanBeReversedLikeAChargeback() throws Exception {
        String topUp = body(topUp(asha, "fund-asha-02", "50.00")).get("transactionId").asText();

        assertThat(adminPost("/api/v1/admin/transactions/" + topUp + "/reversal", "Card chargeback")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(balance(asha)).isEqualTo("1000.00");
    }

    @Test
    void customersCannotUseAdminActions() throws Exception {
        String txn = sendRavi("send-0005", "1.00");
        assertThat(mvc.perform(post("/api/v1/admin/transactions/" + txn + "/reversal")
                .header("Authorization", bearer(ravi)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"give it back\"}")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
}
