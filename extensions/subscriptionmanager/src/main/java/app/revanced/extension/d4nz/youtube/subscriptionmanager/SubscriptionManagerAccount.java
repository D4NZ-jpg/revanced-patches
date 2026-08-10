package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/** Privacy-preserving state namespace supplied by a future active-account hook. */
public final class SubscriptionManagerAccount {
    public static final String UNRESOLVED_NAMESPACE = "unresolved";
    public static final String INCOGNITO_NAMESPACE = "incognito";

    private static final int MAX_ACCOUNT_IDENTIFIER_LENGTH = 2048;
    private static final String ACCOUNT_NAMESPACE_PREFIX = "account-";

    private static final SubscriptionManagerAccount UNRESOLVED = new SubscriptionManagerAccount(
            UNRESOLVED_NAMESPACE,
            false
    );
    private static final SubscriptionManagerAccount INCOGNITO = new SubscriptionManagerAccount(
            INCOGNITO_NAMESPACE,
            false
    );

    private final String namespace;
    private final boolean persistent;

    private SubscriptionManagerAccount(String namespace, boolean persistent) {
        this.namespace = namespace;
        this.persistent = persistent;
    }

    public static SubscriptionManagerAccount unresolved() {
        return UNRESOLVED;
    }

    public static SubscriptionManagerAccount incognito() {
        return INCOGNITO;
    }

    public static SubscriptionManagerAccount fromIdentifier(String accountIdentifier) {
        if (accountIdentifier == null || accountIdentifier.trim().isEmpty()) {
            return unresolved();
        }

        String normalizedIdentifier = accountIdentifier.trim();
        if (normalizedIdentifier.length() > MAX_ACCOUNT_IDENTIFIER_LENGTH) {
            normalizedIdentifier = normalizedIdentifier.substring(0, MAX_ACCOUNT_IDENTIFIER_LENGTH);
        }

        return new SubscriptionManagerAccount(
                ACCOUNT_NAMESPACE_PREFIX + SubscriptionManagerHash.sha256Hex(normalizedIdentifier),
                true
        );
    }

    public String getNamespace() {
        return namespace;
    }

    public boolean isPersistent() {
        return persistent;
    }

    String stateKey() {
        return SubscriptionManagerPreferences.STATE_KEY_PREFIX + namespace;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SubscriptionManagerAccount)) {
            return false;
        }
        SubscriptionManagerAccount that = (SubscriptionManagerAccount) other;
        return persistent == that.persistent && namespace.equals(that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, persistent);
    }
}

/** Shared privacy-preserving hashing for persisted namespaces and debug diagnostics. */
final class SubscriptionManagerHash {
    private static final int SHORT_FINGERPRINT_LENGTH = 12;

    private SubscriptionManagerHash() {
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    static String shortFingerprint(String value) {
        return sha256Hex(value).substring(0, SHORT_FINGERPRINT_LENGTH);
    }
}
