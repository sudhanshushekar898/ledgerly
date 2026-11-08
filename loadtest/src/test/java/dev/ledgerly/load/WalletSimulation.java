package dev.ledgerly.load;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Closed-model load test: a fixed number of concurrent users, each sending a request as soon as the previous one
 * returns, for a fixed time. That measures throughput at a given concurrency, plus latency percentiles.
 *
 * scenario=transfer : user i pays a random other user ₹1 (row locks on two wallets, outbox, idempotency).
 * scenario=topup    : everyone tops up at once (every request also locks the system top-up source account).
 *
 * Synthetic users are created through the public API before the run (same validation, bcrypt and wallet creation as
 * real sign-ups), so the numbers include nothing the product would not do.
 */
public class WalletSimulation extends Simulation {

    private static final String BASE = System.getProperty("baseUrl", "http://localhost:8090");
    private static final String SCENARIO = System.getProperty("scenario", "transfer");
    private static final int USERS = Integer.getInteger("users", 50);
    private static final int SECONDS = Integer.getInteger("seconds", 60);
    private static final int ACCOUNTS = Integer.getInteger("accounts", 100);
    private static final String PASSWORD = "Correct-Horse-Battery-9";
    private static final Pattern TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    private record Account(String email, String token) {
    }

    private final List<Account> accounts = createAccounts();

    /** Runs once before the load phase, outside the measurement. */
    private static List<Account> createAccounts() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String run = Long.toString(System.currentTimeMillis(), 36);
        List<Account> created = Collections.synchronizedList(new ArrayList<>());
        Stream.iterate(0, i -> i + 1).limit(ACCOUNTS).parallel().forEach(i -> {
            String email = "load-" + run + "-" + i + "@example.com";
            String body = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Load User " + i + "\"}";
            String token = extractToken(post(client, "/api/v1/auth/register", null, null, body));
            // Fund every wallet so transfers never fail for lack of money (₹1 each, far below any limit).
            post(client, "/api/v1/wallet/topups", token, UUID.randomUUID().toString(), "{\"amount\":\"10000.00\"}");
            created.add(new Account(email, token));
        });
        System.out.printf("Created and funded %d synthetic accounts%n", created.size());
        return List.copyOf(created);
    }

    private static String post(HttpClient client, String path, String token, String key, String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(path + " -> " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("Setup request failed: " + path, e);
        }
    }

    private static String extractToken(String body) {
        Matcher m = TOKEN.matcher(body);
        if (!m.find()) throw new IllegalStateException("No access token in " + body);
        return m.group(1);
    }

    /** Each virtual user is one account; it pays random other accounts. */
    private Iterator<Map<String, Object>> feeder() {
        return Stream.generate(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Account from = accounts.get(random.nextInt(accounts.size()));
            Account to;
            do {
                to = accounts.get(random.nextInt(accounts.size()));
            } while (to == from);
            return Map.<String, Object>of("token", from.token(), "recipient", to.email(), "key", UUID.randomUUID().toString());
        }).iterator();
    }

    private final HttpProtocolBuilder protocol = http.baseUrl(BASE)
            .contentTypeHeader("application/json")
            .acceptHeader("application/json")
            .shareConnections();

    private final ChainBuilder transfer = feed(feeder()).exec(
            http("transfer").post("/api/v1/transfers")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{key}")
                    .body(StringBody("{\"recipientEmail\":\"#{recipient}\",\"amount\":\"1.00\"}"))
                    .check(status().is(201)));

    private final ChainBuilder topUp = feed(feeder()).exec(
            http("topup").post("/api/v1/wallet/topups")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{key}")
                    .body(StringBody("{\"amount\":\"1.00\"}"))
                    .check(status().is(201)));

    private final ScenarioBuilder load = scenario(SCENARIO)
            .forever().on("topup".equals(SCENARIO) ? topUp : transfer);

    {
        setUp(load.injectClosed(constantConcurrentUsers(USERS).during(SECONDS)))
                .protocols(protocol)
                .maxDuration(Duration.ofSeconds(SECONDS))
                .assertions(global().failedRequests().percent().lt(1.0), details(SCENARIO).successfulRequests().percent().gt(99.0));
    }
}
