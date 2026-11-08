package dev.ledgerly.wallet.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param limitZone the calendar day for the daily limit ends at midnight in this zone (customers are in India)
 */
@ConfigurationProperties("ledgerly.wallet")
public record WalletProperties(
        @DefaultValue("100000.00") BigDecimal maxTopUp,
        @DefaultValue("25000.00") BigDecimal maxTransfer,
        @DefaultValue("50000.00") BigDecimal dailyTransferLimit,
        @DefaultValue("Asia/Kolkata") ZoneId limitZone,
        @DefaultValue("24h") Duration idempotencyKeyTtl) {
}
