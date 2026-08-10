package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.android.libraries.youtube.account.identity.AccountIdentity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SubscriptionManagerAccountHookTest {
    @Before
    public void setUp() {
        SubscriptionManager.disable();
        SubscriptionManagerAccountHook.resetForTesting();
    }

    @After
    public void tearDown() {
        SubscriptionManager.disable();
        SubscriptionManagerAccountHook.resetForTesting();
    }

    @Test
    public void normalIdentityMapsToPersistentHashedNamespaceAndIsIdempotent() {
        SubscriptionManagerState state = new SubscriptionManagerState(
                new SubscriptionManagerStateTest.InMemoryStore());
        AccountIdentity identity = identity("stable-id", false);

        SubscriptionManagerAccountHook.updateState(state, identity);
        SubscriptionManagerState.Snapshot snapshot = state.snapshot();
        SubscriptionManagerAccountHook.updateState(state, identity);

        assertTrue(state.getAccount().isPersistent());
        assertTrue(state.getAccount().getNamespace().startsWith("account-"));
        assertFalse(state.getAccount().getNamespace().contains("stable-id"));
        assertSame(snapshot, state.snapshot());
    }

    @Test
    public void nullIdentityMapsToUnresolvedAndClearsPreviousAccountSnapshot() {
        SubscriptionManagerState state = new SubscriptionManagerState(
                new SubscriptionManagerStateTest.InMemoryStore());
        SubscriptionManagerAccountHook.updateState(state, identity("stable-id", false));
        state.manuallyHideVideo("account-video");

        SubscriptionManagerAccountHook.updateState(state, null);

        assertEquals(SubscriptionManagerAccount.UNRESOLVED_NAMESPACE,
                state.getAccount().getNamespace());
        assertFalse(state.getAccount().isPersistent());
        assertFalse(state.shouldHideVideo("account-video"));
    }

    @Test
    public void incognitoIdentityIsNonPersistentAndDoesNotReadIdentityId() {
        SubscriptionManagerStateTest.InMemoryStore store =
                new SubscriptionManagerStateTest.InMemoryStore();
        SubscriptionManagerState state = new SubscriptionManagerState(store);
        AccountIdentity incognito = new AccountIdentity() {
            @Override
            public String d() {
                throw new AssertionError("Incognito identity ID must not be read");
            }

            @Override
            public boolean g() {
                return true;
            }
        };

        SubscriptionManagerAccountHook.updateState(state, incognito);
        state.manuallyHideVideo("incognito-video");

        assertEquals(SubscriptionManagerAccount.INCOGNITO_NAMESPACE,
                state.getAccount().getNamespace());
        assertFalse(state.getAccount().isPersistent());
        assertTrue(store.values.isEmpty());
    }

    @Test
    public void getterFailureMapsToUnresolved() {
        SubscriptionManagerState state = new SubscriptionManagerState(
                new SubscriptionManagerStateTest.InMemoryStore());
        SubscriptionManagerAccountHook.updateState(state, identity("stable-id", false));
        state.manuallyHideVideo("account-video");
        AccountIdentity broken = new AccountIdentity() {
            @Override
            public String d() {
                throw new LinkageError("simulated getter failure");
            }

            @Override
            public boolean g() {
                return false;
            }
        };

        SubscriptionManagerAccountHook.updateState(state, broken);

        assertEquals(SubscriptionManagerAccount.UNRESOLVED_NAMESPACE,
                state.getAccount().getNamespace());
        assertFalse(state.shouldHideVideo("account-video"));
    }

    @Test
    public void accountTransitionsDoNotBleedStateAcrossAccounts() {
        SubscriptionManagerState state = new SubscriptionManagerState(
                new SubscriptionManagerStateTest.InMemoryStore());
        AccountIdentity first = identity("first-id", false);
        AccountIdentity second = identity("second-id", false);

        SubscriptionManagerAccountHook.updateState(state, first);
        state.manuallyHideVideo("first-video");
        SubscriptionManagerAccountHook.updateState(state, second);
        assertFalse(state.shouldHideVideo("first-video"));
        state.manuallyHideVideo("second-video");

        SubscriptionManagerAccountHook.updateState(state, first);
        assertTrue(state.shouldHideVideo("first-video"));
        assertFalse(state.shouldHideVideo("second-video"));
    }

    @Test
    public void accountObservedBeforeInitializationIsAppliedLater() {
        SubscriptionManagerAccountHook.setAccount(identity("queued-id", false));
        SubscriptionManagerState state = new SubscriptionManagerState(
                new SubscriptionManagerStateTest.InMemoryStore());

        SubscriptionManagerAccountHook.applyPendingAccount(state);

        assertTrue(state.getAccount().isPersistent());
        assertFalse(state.getAccount().getNamespace().contains("queued-id"));
    }

    @Test
    public void pendingAccountSurvivesTransientStoreFailure() {
        AtomicBoolean failNextRead = new AtomicBoolean(true);
        SubscriptionManagerPreferences.Store store = new SubscriptionManagerPreferences.Store() {
            final SubscriptionManagerStateTest.InMemoryStore delegate =
                    new SubscriptionManagerStateTest.InMemoryStore();

            @Override
            public String getString(String key) {
                if (failNextRead.getAndSet(false)) {
                    throw new IllegalStateException("simulated transient read failure");
                }
                return delegate.getString(key);
            }

            @Override
            public void putString(String key, String value) {
                delegate.putString(key, value);
            }

            @Override
            public void remove(String key) {
                delegate.remove(key);
            }
        };
        SubscriptionManagerState state = new SubscriptionManagerState(store);
        long generation = SubscriptionManagerAccountHook.beginTransition();
        assertTrue(SubscriptionManagerAccountHook.finishTransition(
                generation, identity("retry-id", false)));

        try {
            SubscriptionManagerAccountHook.applyPendingAccount(state);
            throw new AssertionError("Expected transient store failure");
        } catch (IllegalStateException expected) {
            assertEquals(SubscriptionManagerAccount.UNRESOLVED_NAMESPACE,
                    state.getAccount().getNamespace());
        }

        SubscriptionManagerAccountHook.applyPendingAccount(state);
        assertEquals(SubscriptionManagerAccount.fromIdentifier("retry-id"), state.getAccount());
    }

    @Test
    public void laterTransitionWinsWhenEarlierGetterFinishesLast() throws Exception {
        CountDownLatch getterEntered = new CountDownLatch(1);
        CountDownLatch releaseGetter = new CountDownLatch(1);
        AtomicBoolean earlierAccepted = new AtomicBoolean(true);
        Thread earlier = new Thread(() -> {
            long generation = SubscriptionManagerAccountHook.beginTransition();
            earlierAccepted.set(SubscriptionManagerAccountHook.finishTransition(
                    generation, blockingIdentity("earlier-id", getterEntered, releaseGetter)));
        });

        earlier.start();
        assertTrue(getterEntered.await(5, TimeUnit.SECONDS));
        long laterGeneration = SubscriptionManagerAccountHook.beginTransition();
        assertTrue(SubscriptionManagerAccountHook.finishTransition(
                laterGeneration, identity("later-id", false)));
        releaseGetter.countDown();
        earlier.join(5000);

        assertFalse(earlier.isAlive());
        assertFalse(earlierAccepted.get());
        assertPendingAccount("later-id");
    }

    @Test
    public void supersededGenerationCannotPublishAfterNewerTransitionStarts() {
        SubscriptionManagerState state = new SubscriptionManagerState(
                new SubscriptionManagerStateTest.InMemoryStore());
        long earlierGeneration = SubscriptionManagerAccountHook.beginTransition();
        assertTrue(SubscriptionManagerAccountHook.finishTransition(
                earlierGeneration, identity("earlier-id", false)));

        long laterGeneration = SubscriptionManagerAccountHook.beginTransition();

        assertFalse(SubscriptionManagerAccountHook.applyPendingAccount(
                state, earlierGeneration));
        assertTrue(SubscriptionManagerAccountHook.applyPendingAccount(
                state, laterGeneration));
        assertEquals(SubscriptionManagerAccount.UNRESOLVED_NAMESPACE,
                state.getAccount().getNamespace());

        assertTrue(SubscriptionManagerAccountHook.finishTransition(
                laterGeneration, identity("later-id", false)));
        assertTrue(SubscriptionManagerAccountHook.applyPendingAccount(
                state, laterGeneration));
        assertEquals(SubscriptionManagerAccount.fromIdentifier("later-id"), state.getAccount());
    }

    @Test
    public void startupHydrationCannotOverrideStartedTransition() throws Exception {
        CountDownLatch getterEntered = new CountDownLatch(1);
        CountDownLatch releaseGetter = new CountDownLatch(1);
        AtomicBoolean startupAccepted = new AtomicBoolean(true);
        Thread startup = new Thread(() -> startupAccepted.set(
                SubscriptionManagerAccountHook.rememberStartup(
                        blockingIdentity("startup-id", getterEntered, releaseGetter))));

        startup.start();
        assertTrue(getterEntered.await(5, TimeUnit.SECONDS));
        long generation = SubscriptionManagerAccountHook.beginTransition();
        assertTrue(SubscriptionManagerAccountHook.finishTransition(
                generation, identity("transition-id", false)));
        releaseGetter.countDown();
        startup.join(5000);

        assertFalse(startup.isAlive());
        assertFalse(startupAccepted.get());
        assertPendingAccount("transition-id");
    }

    private static void assertPendingAccount(String id) {
        SubscriptionManagerState state = new SubscriptionManagerState(
                new SubscriptionManagerStateTest.InMemoryStore());
        SubscriptionManagerAccountHook.applyPendingAccount(state);
        assertEquals(SubscriptionManagerAccount.fromIdentifier(id), state.getAccount());
    }

    private static AccountIdentity blockingIdentity(
            final String id,
            CountDownLatch getterEntered,
            CountDownLatch releaseGetter
    ) {
        return new AccountIdentity() {
            @Override
            public String d() {
                getterEntered.countDown();
                try {
                    if (!releaseGetter.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release identity getter");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return id;
            }

            @Override
            public boolean g() {
                return false;
            }
        };
    }

    private static AccountIdentity identity(final String id, final boolean incognito) {
        return new AccountIdentity() {
            @Override
            public String d() {
                return id;
            }

            @Override
            public boolean g() {
                return incognito;
            }
        };
    }
}
