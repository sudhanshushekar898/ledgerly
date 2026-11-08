package dev.ledgerly.identity.service;

import java.util.Locale;

final class Emails {

    private Emails() {}

    /** Emails are compared and stored trimmed and lower-cased. */
    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
