package dev.ledgerly.notification.service;

import dev.ledgerly.identity.UserDirectory;
import dev.ledgerly.ledger.MoneyMoved;
import dev.ledgerly.notification.repository.NotificationStore;
import dev.ledgerly.notification.repository.NotificationStore.Row;
import dev.ledgerly.shared.persistence.Ids;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Turns ledger events into notifications, exactly once per event, and pushes them to open browser tabs. */
@Service
public class NotificationService {

    static final String CONSUMER = "notifications";
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationStore store;
    private final UserDirectory directory;
    private final LiveStream live;
    private final Clock clock;

    NotificationService(NotificationStore store, UserDirectory directory, LiveStream live, Clock clock) {
        this.store = store;
        this.directory = directory;
        this.live = live;
        this.clock = clock;
    }

    /**
     * The inbox row and the notifications commit together. A redelivered event (Kafka is at-least-once, and the
     * outbox relay may resend after a crash) finds its inbox row and is skipped.
     */
    @Transactional
    public void handle(MoneyMoved event) {
        if (!store.firstDelivery(CONSUMER, event.eventId(), clock.instant())) {
            log.info("Duplicate event {} skipped", event.eventId());
            return;
        }
        Map<UUID, String> names = directory.namesOf(Stream.of(event.payerUserId(), event.payeeUserId()).filter(Objects::nonNull).toList());
        List<Row> rows = new ArrayList<>();
        switch (event.kind()) {
            case TOPUP -> add(rows, event, event.payeeUserId(), "MONEY_ADDED", null);
            case TRANSFER -> add(rows, event, event.payeeUserId(), "MONEY_RECEIVED", names.get(event.payerUserId()));
            case REVERSAL -> {
                // The payee of a reversal gets money back; the payer has it taken back.
                add(rows, event, event.payeeUserId(), "PAYMENT_RETURNED", names.get(event.payerUserId()));
                add(rows, event, event.payerUserId(), "PAYMENT_REVERSED", names.get(event.payeeUserId()));
            }
        }
        rows.forEach(store::insert);
        // Push only once the rows are durable: a rollback must never show a notification that does not exist.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rows.forEach(row -> live.push(row.userId(), "notification", NotificationView.from(row)));
            }
        });
    }

    private void add(List<Row> rows, MoneyMoved event, UUID userId, String kind, String counterparty) {
        if (userId != null) {
            rows.add(new Row(Ids.newId(), userId, kind, event.amount(), counterparty, event.memo(), event.transactionId(),
                    event.occurredAt(), false));
        }
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UUID userId) {
        return new Inbox(store.unreadCount(userId), store.latest(userId, 20).stream().map(NotificationView::from).toList());
    }

    @Transactional
    public void markAllRead(UUID userId) {
        store.markAllRead(userId, clock.instant());
    }

    public record Inbox(long unread, List<NotificationView> items) {
    }
}
