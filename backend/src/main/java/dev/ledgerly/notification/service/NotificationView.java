package dev.ledgerly.notification.service;

import dev.ledgerly.notification.repository.NotificationStore.Row;
import dev.ledgerly.shared.money.Amounts;
import java.time.Instant;
import java.util.UUID;

/** What the browser receives, on the list endpoint and on the live stream. Amount as a string, like everywhere. */
public record NotificationView(UUID id, String kind, String amount, String counterpartyName, String memo, UUID transactionId,
                               Instant createdAt, boolean read) {

    static NotificationView from(Row row) {
        return new NotificationView(row.id(), row.kind(), Amounts.format(row.amount()), row.counterpartyName(), row.memo(),
                row.transactionId(), row.createdAt(), row.read());
    }
}
