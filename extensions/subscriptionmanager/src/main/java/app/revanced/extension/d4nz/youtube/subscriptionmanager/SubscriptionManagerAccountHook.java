package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import com.google.android.libraries.youtube.account.identity.AccountIdentity;

/** Receives active-account transitions from the verified YouTube 20.40.45 identity store. */
public final class SubscriptionManagerAccountHook {
    private static final Object ACCOUNT_LOCK = new Object();

    private static long transitionGeneration;
    private static SubscriptionManagerAccount pendingAccount =
            SubscriptionManagerAccount.unresolved();

    private SubscriptionManagerAccountHook() {
    }

    /** Applies a committed account transition without reading an account name or email address. */
    public static void setAccount(AccountIdentity identity) {
        long generation = beginTransition();
        applyLatestAccount(generation);
        if (finishTransition(generation, identity)) {
            applyLatestAccount(generation);
        }
    }

    /** Applies startup hydration only if no newer account transition has started. */
    public static void setAccountFromStartup(Object identity) {
        if (rememberStartup(identity)) {
            applyLatestAccount(0);
        }
    }

    static long beginTransition() {
        synchronized (ACCOUNT_LOCK) {
            transitionGeneration++;
            pendingAccount = SubscriptionManagerAccount.unresolved();
            return transitionGeneration;
        }
    }

    static boolean finishTransition(long generation, AccountIdentity identity) {
        SubscriptionManagerAccount account = resolveSafely(identity);
        synchronized (ACCOUNT_LOCK) {
            if (generation != transitionGeneration) {
                return false;
            }
            pendingAccount = account;
            return true;
        }
    }

    static boolean rememberStartup(Object identity) {
        SubscriptionManagerAccount account = resolveSafely(identity);
        synchronized (ACCOUNT_LOCK) {
            if (transitionGeneration != 0) {
                return false;
            }
            pendingAccount = account;
            return true;
        }
    }

    static void updateState(SubscriptionManagerState state, AccountIdentity identity) {
        state.setAccount(resolveSafely(identity));
    }

    static void applyPendingAccount(SubscriptionManagerState state) {
        if (state == null) {
            return;
        }
        synchronized (ACCOUNT_LOCK) {
            state.setAccount(pendingAccount);
        }
    }

    static boolean applyPendingAccount(
            SubscriptionManagerState state, long expectedGeneration) {
        if (state == null) {
            return false;
        }
        synchronized (ACCOUNT_LOCK) {
            if (expectedGeneration != transitionGeneration) {
                return false;
            }
            state.setAccount(pendingAccount);
            return true;
        }
    }

    private static void applyLatestAccount(long expectedGeneration) {
        try {
            SubscriptionManager.initialize();
            applyPendingAccount(
                    SubscriptionManager.initializedState(), expectedGeneration);
        } catch (Throwable ignored) {
            // Keep the latest pending account for a later successful initialization.
        }
    }

    private static SubscriptionManagerAccount resolveSafely(Object identity) {
        try {
            if (!(identity instanceof AccountIdentity)) {
                return SubscriptionManagerAccount.unresolved();
            }
            AccountIdentity accountIdentity = (AccountIdentity) identity;
            if (accountIdentity.g()) {
                return SubscriptionManagerAccount.incognito();
            }
            return SubscriptionManagerAccount.fromIdentifier(accountIdentity.d());
        } catch (Throwable ignored) {
            return SubscriptionManagerAccount.unresolved();
        }
    }

    static void resetForTesting() {
        synchronized (ACCOUNT_LOCK) {
            transitionGeneration = 0;
            pendingAccount = SubscriptionManagerAccount.unresolved();
        }
    }
}
