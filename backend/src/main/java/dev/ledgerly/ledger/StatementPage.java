package dev.ledgerly.ledger;

import java.util.List;

/** Newest first. {@code nextBefore} is the cursor for the next (older) page, or null on the last page. */
public record StatementPage(List<StatementLine> lines, Long nextBefore) {
}
