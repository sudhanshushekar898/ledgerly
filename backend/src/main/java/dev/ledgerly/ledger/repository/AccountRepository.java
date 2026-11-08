package dev.ledgerly.ledger.repository;

import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByOwnerUserIdAndType(UUID ownerUserId, AccountType type);

    /*
     * Read paths return projections, not entities, on purpose. If an Account entity were already managed in the
     * transaction when post() locks it, Hibernate would keep the cached (stale) instance and fail the lock with an
     * optimistic-locking error under concurrency. TopUpConcurrencyIT found this; @Version stopped it from becoming
     * a lost update.
     */
    @Query("select new dev.ledgerly.ledger.AccountView(a.id, a.ownerUserId, a.type, a.currency, a.balance, a.status) "
            + "from Account a where a.ownerUserId = :owner and a.type = dev.ledgerly.ledger.AccountType.USER_WALLET")
    Optional<AccountView> findUserWalletView(@Param("owner") UUID ownerUserId);

    /** All shards of a system account, in shard order. */
    @Query("select new dev.ledgerly.ledger.AccountView(a.id, a.ownerUserId, a.type, a.currency, a.balance, a.status) "
            + "from Account a where a.type = :type and a.ownerUserId is null order by a.shard")
    List<AccountView> findSystemAccountViews(@Param("type") AccountType type);

    @Query("select new dev.ledgerly.ledger.AccountView(a.id, a.ownerUserId, a.type, a.currency, a.balance, a.status) "
            + "from Account a where a.id = :id")
    Optional<AccountView> findView(@Param("id") UUID id);

    /**
     * Row-locks the accounts in ascending id order (ADR-003). Every posting locks through this one query, so two
     * transfers A->B and B->A both lock min(A,B) first and cannot deadlock. PostgreSQL does the sorting on purpose:
     * java.util.UUID.compareTo compares signed longs and does not match PostgreSQL's uuid ordering.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllInIdOrder(@Param("ids") Collection<UUID> ids);
}
