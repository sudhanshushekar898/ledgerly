package dev.ledgerly.wallet.demo;

import dev.ledgerly.identity.CustomerSignUp;
import dev.ledgerly.shared.error.DomainException;
import dev.ledgerly.shared.persistence.DemoClock;
import dev.ledgerly.wallet.config.WalletProperties;
import dev.ledgerly.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Demo only ({@code ledgerly.demo.seed=true}): gives a fresh stack 60 days of believable history so the insights chart,
 * activity and contacts have something to show.
 * <ul>
 *   <li>Every payment goes through {@link WalletService}, the same path as the API: idempotency keys, row locks,
 *       limits, double entry, outbox events. Nothing is inserted behind the ledger's back.</li>
 *   <li>The past is reached by moving the {@link DemoClock} to each payment's time, in time order, so balances,
 *       sequence numbers and timestamps stay consistent.</li>
 *   <li>Deterministic (fixed random seed) and run once: it does nothing if the demo customer already exists.</li>
 *   <li>All people are synthetic, on the reserved example.com domain.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "ledgerly.demo.seed", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_EMAIL = "asha@demo.example.com";
    static final int DAYS = 60;

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final Map<String, String> FRIENDS = Map.of(
            "ravi@demo.example.com", "Ravi Kumar",
            "meera@demo.example.com", "Meera Iyer",
            "kabir@demo.example.com", "Kabir Shah",
            "priya@demo.example.com", "Priya Nair");
    private static final String[] OUT_MEMOS = {"Dinner at Toit", "Cab share", "Rent split", "Movie tickets", "Groceries",
        "Goa trip advance", "Coffee", "Electricity bill share", "Birthday gift", "Concert tickets", null, null};
    private static final String[] IN_MEMOS = {"Your share of dinner", "Paid back, thanks!", "Cab money", "Trip refund",
        null};

    private final CustomerSignUp signUp;
    private final WalletService wallet;
    private final WalletProperties properties;
    private final DemoClock clock;
    private final String password;

    DemoDataSeeder(CustomerSignUp signUp, WalletService wallet, WalletProperties properties, DemoClock clock,
                   @Value("${ledgerly.demo.password}") String password) {
        this.signUp = signUp;
        this.wallet = wallet;
        this.properties = properties;
        this.clock = clock;
        this.password = password;
    }

    private record Step(ZonedDateTime at, Runnable action) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (signUp.exists(DEMO_EMAIL)) {
            log.info("Demo data already present; skipping");
            return;
        }
        Random random = new Random(2026);
        LocalDate today = LocalDate.now(clock.withZone(properties.limitZone()));
        ZonedDateTime start = today.minusDays(DAYS).atTime(9, 0).atZone(properties.limitZone());
        int[] posted = {0};
        try {
            clock.travelTo(start.toInstant());
            UUID asha = signUp.register(DEMO_EMAIL, password, "Asha Rao");
            List<String> friendEmails = FRIENDS.keySet().stream().sorted().toList();
            List<UUID> friends = new ArrayList<>();
            for (String email : friendEmails) {
                friends.add(signUp.register(email, password, FRIENDS.get(email)));
            }
            Seeding seed = new Seeding(posted);
            seed.topUp(asha, "12000.00");
            friends.forEach(f -> seed.topUp(f, "20000.00"));

            for (int day = 1; day < DAYS; day++) {
                ZonedDateTime date = start.plusDays(day).withHour(0);
                List<Step> steps = new ArrayList<>();
                if (day % 30 == 1) {
                    steps.add(new Step(at(date, 9, random), () -> seed.topUp(asha, "25000.00")));
                } else if (random.nextDouble() < 0.12) {
                    String amount = rupees(2000 + random.nextInt(5) * 1000);
                    steps.add(new Step(at(date, 8, random), () -> seed.topUp(asha, amount)));
                }
                int outgoing = random.nextDouble() < 0.25 ? 0 : 1 + random.nextInt(2);
                for (int i = 0; i < outgoing; i++) {
                    String to = friendEmails.get(random.nextInt(friendEmails.size()));
                    String amount = rupees(150 + random.nextInt(34) * 100);
                    String memo = OUT_MEMOS[random.nextInt(OUT_MEMOS.length)];
                    steps.add(new Step(at(date, 10, random), () -> seed.transfer(asha, to, amount, memo)));
                }
                if (random.nextDouble() < 0.35) {
                    UUID from = friends.get(random.nextInt(friends.size()));
                    String amount = rupees(200 + random.nextInt(20) * 100);
                    String memo = IN_MEMOS[random.nextInt(IN_MEMOS.length)];
                    steps.add(new Step(at(date, 10, random), () -> seed.transfer(from, DEMO_EMAIL, amount, memo)));
                }
                if (day % 14 == 0) {
                    friends.forEach(f -> steps.add(new Step(at(date, 8, random), () -> seed.topUp(f, "5000.00"))));
                }
                steps.sort(Comparator.comparing(Step::at));
                for (Step step : steps) {
                    clock.travelTo(step.at().toInstant());
                    step.action().run();
                }
            }
        } finally {
            clock.backToNow();
        }
        log.info("Demo data seeded: {} payments over {} days for {}", posted[0], DAYS, DEMO_EMAIL);
    }

    /** A random moment of that day from {@code fromHour} to 22:00, in the customers' zone. */
    private static ZonedDateTime at(ZonedDateTime day, int fromHour, Random random) {
        return day.withHour(fromHour + random.nextInt(22 - fromHour)).withMinute(random.nextInt(60))
                .withSecond(random.nextInt(60));
    }

    private static String rupees(int amount) {
        return amount + ".00";
    }

    /** Runs payments through the real service; a business rejection (funds, limit) is skipped, as a user would. */
    private final class Seeding {
        private final int[] posted;
        private int key;

        Seeding(int[] posted) {
            this.posted = posted;
        }

        private String nextKey() {
            return "demo-seed-%05d".formatted(++key);
        }

        void topUp(UUID user, String amount) {
            run(() -> wallet.topUp(user, nextKey(), new BigDecimal(amount)));
        }

        void transfer(UUID from, String toEmail, String amount, String memo) {
            run(() -> wallet.transfer(from, nextKey(), toEmail, new BigDecimal(amount), memo));
        }

        private void run(Runnable payment) {
            try {
                payment.run();
                posted[0]++;
            } catch (DomainException e) {
                log.debug("Demo payment skipped: {}", e.getMessage());
            }
        }
    }
}
