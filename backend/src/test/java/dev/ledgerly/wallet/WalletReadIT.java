package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerly.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Receipts, insights, contacts and recipient verification: what each customer may see, and nothing more. */
class WalletReadIT extends AbstractIntegrationTest {

    private String asha;
    private String ravi;

    @BeforeEach
    void setUp() throws Exception {
        asha = accessToken(register("asha@example.com", STRONG_PASSWORD));
        ravi = accessToken(register("ravi@example.com", STRONG_PASSWORD));
        topUp(asha, "fund-asha-01", "1000.00");
    }

    private MvcResult call(String token, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer(token))).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String token, String path) throws Exception {
        MvcResult result = call(token, path);
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return json(result);
    }

    private String send(String from, String key, String to, String amount) throws Exception {
        MvcResult result = transfer(from, key, to, amount, "Dinner");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json(result).get("transactionId").asText();
    }

    @Test
    void receiptShowsBothLedgerLinesButOnlyTheViewersOwnBalance() throws Exception {
        String txn = send(asha, "send-0001", "ravi@example.com", "250.50");

        JsonNode mine = getJson(asha, "/api/v1/wallet/transactions/" + txn);
        assertThat(mine.get("kind").asText()).isEqualTo("TRANSFER");
        assertThat(mine.get("direction").asText()).isEqualTo("DEBIT");
        assertThat(mine.get("amount").asText()).isEqualTo("250.50");
        assertThat(mine.get("memo").asText()).isEqualTo("Dinner");
        assertThat(mine.get("counterpartyName").asText()).isEqualTo("Test User");
        assertThat(mine.get("balanced").asBoolean()).isTrue();
        JsonNode lines = mine.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("account").asText()).isEqualTo("YOUR_WALLET");
        assertThat(lines.get(0).get("direction").asText()).isEqualTo("DEBIT");
        assertThat(lines.get(0).get("balanceAfter").asText()).isEqualTo("749.50");
        assertThat(lines.get(1).get("account").asText()).isEqualTo("CUSTOMER_WALLET");
        assertThat(lines.get(1).get("label").asText()).isEqualTo("Test User's wallet");
        assertThat(lines.get(1).get("direction").asText()).isEqualTo("CREDIT");
        assertThat(lines.get(1).has("balanceAfter")).as("another customer's balance is private").isFalse();

        JsonNode theirs = getJson(ravi, "/api/v1/wallet/transactions/" + txn);
        assertThat(theirs.get("direction").asText()).isEqualTo("CREDIT");
        assertThat(theirs.get("lines").get(1).get("account").asText()).isEqualTo("YOUR_WALLET");
        assertThat(theirs.get("lines").get(1).get("balanceAfter").asText()).isEqualTo("250.50");
        assertThat(theirs.get("lines").get(0).has("balanceAfter")).isFalse();
    }

    @Test
    void outsidersCannotTellWhetherATransactionExists() throws Exception {
        String txn = send(asha, "send-0002", "ravi@example.com", "10.00");
        String meera = accessToken(register("meera@example.com", STRONG_PASSWORD));

        MvcResult stranger = call(meera, "/api/v1/wallet/transactions/" + txn);
        MvcResult unknown = call(asha, "/api/v1/wallet/transactions/" + UUID.randomUUID());

        assertThat(stranger.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(stranger).get("code").asText()).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(unknown).get("detail")).isEqualTo(json(stranger).get("detail"));
        assertThat(call(asha, "/api/v1/wallet/transactions/not-a-uuid").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void topUpReceiptNamesTheSystemSourceAccount() throws Exception {
        String topUp = json(topUp(asha, "fund-asha-02", "50.00")).get("transactionId").asText();

        JsonNode lines = getJson(asha, "/api/v1/wallet/transactions/" + topUp).get("lines");

        assertThat(lines.get(0).get("account").asText()).isEqualTo("TOPUP_SOURCE");
        assertThat(lines.get(0).get("label").asText()).isEqualTo("Ledgerly top-up source");
        assertThat(lines.get(0).has("balanceAfter")).isFalse();
        assertThat(lines.get(1).get("account").asText()).isEqualTo("YOUR_WALLET");
        assertThat(lines.get(1).get("balanceAfter").asText()).isEqualTo("1050.00");
    }

    @Test
    void aReversalAndItsOriginalPointAtEachOther() throws Exception {
        String txn = send(asha, "send-0003", "ravi@example.com", "100.00");
        String reversal = json(mvc.perform(post("/api/v1/admin/transactions/" + txn + "/reversal")
                .header("Authorization", bearer(adminToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Sent by mistake\"}")).andReturn()).get("reversalId").asText();

        assertThat(getJson(asha, "/api/v1/wallet/transactions/" + txn).get("reversedBy").asText()).isEqualTo(reversal);
        JsonNode undo = getJson(asha, "/api/v1/wallet/transactions/" + reversal);
        assertThat(undo.get("kind").asText()).isEqualTo("REVERSAL");
        assertThat(undo.get("reverses").asText()).isEqualTo(txn);
        assertThat(undo.get("direction").asText()).isEqualTo("CREDIT");
        assertThat(undo.get("memo").asText()).isEqualTo("Sent by mistake");
    }

    @Test
    void insightsHaveOneEntryPerIndianCalendarDayWithTodaysTotalsAndLimit() throws Exception {
        send(asha, "send-0004", "ravi@example.com", "250.00");

        JsonNode insights = getJson(asha, "/api/v1/wallet/insights?days=30");

        JsonNode series = insights.get("series");
        assertThat(series).hasSize(30);
        JsonNode today = series.get(29);
        assertThat(today.get("date").asText()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString());
        assertThat(today.get("moneyIn").asText()).isEqualTo("1000.00");
        assertThat(today.get("moneyOut").asText()).isEqualTo("250.00");
        assertThat(today.get("closingBalance").asText()).isEqualTo("750.00");
        assertThat(series.get(0).get("moneyIn").asText()).isEqualTo("0.00");
        assertThat(series.get(0).get("closingBalance").asText()).isEqualTo("0.00");
        assertThat(insights.get("totalIn").asText()).isEqualTo("1000.00");
        assertThat(insights.get("totalOut").asText()).isEqualTo("250.00");
        JsonNode limits = insights.get("limits");
        assertThat(limits.get("daily").asText()).isEqualTo("50000.00");
        assertThat(limits.get("perTransfer").asText()).isEqualTo("25000.00");
        assertThat(limits.get("sentToday").asText()).isEqualTo("250.00");
        assertThat(limits.get("remainingToday").asText()).isEqualTo("49750.00");
    }

    @Test
    void insightsWindowIsClampedToSevenToNinetyDays() throws Exception {
        assertThat(getJson(asha, "/api/v1/wallet/insights?days=3").get("series")).hasSize(7);
        assertThat(getJson(asha, "/api/v1/wallet/insights?days=500").get("series")).hasSize(90);
    }

    @Test
    void contactsListRecentCounterpartiesOnceMostRecentFirst() throws Exception {
        accessToken(register("meera@example.com", STRONG_PASSWORD));
        send(asha, "send-0005", "ravi@example.com", "1.00");
        send(asha, "send-0006", "meera@example.com", "1.00");
        send(asha, "send-0007", "ravi@example.com", "1.00");

        JsonNode contacts = getJson(asha, "/api/v1/wallet/contacts");

        assertThat(contacts).hasSize(2);
        assertThat(contacts.get(0).get("email").asText()).isEqualTo("ravi@example.com");
        assertThat(contacts.get(1).get("email").asText()).isEqualTo("meera@example.com");
        // incoming payments count too: Ravi sees Asha
        assertThat(getJson(ravi, "/api/v1/wallet/contacts").get(0).get("email").asText()).isEqualTo("asha@example.com");
    }

    @Test
    void recipientLookupVerifiesWhoWillBePaid() throws Exception {
        JsonNode found = getJson(asha, "/api/v1/recipients?email=RAVI@example.com");
        assertThat(found.get("name").asText()).isEqualTo("Test User");
        assertThat(found.get("email").asText()).isEqualTo("ravi@example.com");

        MvcResult self = call(asha, "/api/v1/recipients?email=asha@example.com");
        assertThat(self.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(self).get("code").asText()).isEqualTo("SAME_ACCOUNT");
        assertThat(call(asha, "/api/v1/recipients?email=nobody@example.com").getResponse().getStatus()).isEqualTo(404);
        adminToken();
        assertThat(call(asha, "/api/v1/recipients?email=operator@example.com").getResponse().getStatus())
                .as("operators cannot receive money").isEqualTo(404);
    }

    @Test
    void readEndpointsAreForCustomersOnly() throws Exception {
        assertThat(mvc.perform(get("/api/v1/wallet/insights")).andReturn().getResponse().getStatus()).isEqualTo(401);
        String admin = adminToken();
        assertThat(call(admin, "/api/v1/wallet/contacts").getResponse().getStatus()).isEqualTo(403);
        assertThat(call(admin, "/api/v1/recipients?email=ravi@example.com").getResponse().getStatus()).isEqualTo(403);
    }
}
