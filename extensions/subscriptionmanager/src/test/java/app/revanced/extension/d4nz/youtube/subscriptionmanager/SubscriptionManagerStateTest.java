package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SubscriptionManagerStateTest {
    @Test
    public void accountIsolationPersistsByHashedNamespace() {
        InMemoryStore store = new InMemoryStore();
        SubscriptionManagerState state = new SubscriptionManagerState(store);

        state.setAccountIdentifier("first@example.com");
        String firstKey = state.getAccount().stateKey();
        state.manuallyHideVideo("video-a");
        String firstStoredKey = state.snapshot().getManuallyHiddenVideoIds().iterator().next();

        state.setAccountIdentifier("second@example.com");
        String secondKey = state.getAccount().stateKey();
        assertFalse(state.shouldHideVideo("video-a"));
        state.manuallyHideVideo("video-a");
        String secondStoredKey = state.snapshot().getManuallyHiddenVideoIds().iterator().next();
        state.manuallyHideVideo("video-b");

        assertFalse(firstKey.contains("first@example.com"));
        assertFalse(secondKey.contains("second@example.com"));
        assertFalse(firstKey.equals(secondKey));

        assertFalse(firstStoredKey.equals(secondStoredKey));
        state.setAccountIdentifier("first@example.com");
        assertTrue(state.shouldHideVideo("video-a"));
        assertFalse(state.shouldHideVideo("video-b"));
        String reloadedFirstStoredKey = state.snapshot().getManuallyHiddenVideoIds().iterator().next();
        assertEquals(firstStoredKey, reloadedFirstStoredKey);
    }

    @Test
    public void restoreSemanticsKeepManualAndWatchedRestoresSeparate() {
        SubscriptionManagerState state = new SubscriptionManagerState(new InMemoryStore());

        state.markVideoHiddenAsWatched("watched-one");
        state.markVideoHiddenAsWatched("watched-two");
        assertTrue(state.shouldHideVideo("watched-one"));

        state.restoreWatchedVideo("watched-one");
        assertFalse(state.shouldHideVideo("watched-one"));
        assertTrue(state.shouldHideVideo("watched-two"));
        assertEquals(1, state.snapshot().getVideoShowOverrideIds().size());
        assertFalse(state.snapshot().getVideoShowOverrideIds().contains("watched-one"));

        // Playback updates must not clear a per-video show override.
        state.markVideoHiddenAsWatched("watched-one");
        assertFalse(state.shouldHideVideo("watched-one"));

        state.manuallyHideVideo("watched-one");
        state.restoreWatchedVideo("watched-one");
        assertTrue(state.shouldHideVideo("watched-one"));

        state.restoreManuallyHiddenVideo("watched-one");
        assertFalse(state.shouldHideVideo("watched-one"));
    }

    @Test
    public void watchedFilteringCanBeToggledWithoutDisablingManualHidesOrOverrides() {
        SubscriptionManagerState state = new SubscriptionManagerState(new InMemoryStore());

        state.markVideoHiddenAsWatched("watched-only");
        state.markVideoHiddenAsWatched("watched-overridden");
        state.restoreWatchedVideo("watched-overridden");
        state.manuallyHideVideo("manual-only");

        assertTrue(state.shouldHideVideo("watched-only", true));
        assertFalse(state.shouldHideVideo("watched-only", false));
        assertFalse(state.shouldHideVideo("watched-overridden", true));
        assertFalse(state.shouldHideVideo("watched-overridden", false));
        assertTrue(state.shouldHideVideo("manual-only", true));
        assertTrue(state.shouldHideVideo("manual-only", false));
    }

    @Test
    public void unresolvedAndIncognitoDoNotPersistState() {
        InMemoryStore store = new InMemoryStore();
        SubscriptionManagerState state = new SubscriptionManagerState(store);

        state.manuallyHideVideo("unresolved-video");
        assertTrue(state.shouldHideVideo("unresolved-video"));
        assertEquals(0, store.values.size());

        state.setIncognito(true);
        state.manuallyHideVideo("incognito-video");
        assertTrue(state.shouldHideVideo("incognito-video"));
        assertFalse(state.shouldHideVideo("unresolved-video"));
        assertEquals(0, store.values.size());

        state.setIncognito(false);
        assertFalse(state.shouldHideVideo("incognito-video"));
        assertEquals(0, store.values.size());
    }

    @Test
    public void legacyRawStateIsRewrittenToEmptyCurrentFormatOnLoad() {
        InMemoryStore store = new InMemoryStore();
        SubscriptionManagerAccount account = SubscriptionManagerAccount.fromIdentifier("legacy-account");
        String legacyRawBase64 = "QWJjX2RlZi0xMjM";
        store.putString(account.stateKey(), "version=1\nmanualVideos=" + legacyRawBase64
                + "\nwatchedVideos=\nshowOverrides=\nchannelIds=\nchannelHandles=\n");

        SubscriptionManagerState state = new SubscriptionManagerState(store, account);

        assertFalse(state.shouldHideVideo("Abc_def-123"));
        assertEquals(SubscriptionManagerStateCodec.serialize(SubscriptionManagerState.Snapshot.empty()),
                store.getString(account.stateKey()));
        assertFalse(store.getString(account.stateKey()).contains(legacyRawBase64));
    }

    @Test
    public void malformedStateFailsOpenAndCanBeReplaced() {
        InMemoryStore store = new InMemoryStore();
        String key = SubscriptionManagerAccount.fromIdentifier("account").stateKey();
        store.putString(key, "not-a-valid-state");

        SubscriptionManagerState state = new SubscriptionManagerState(store);
        state.setAccountIdentifier("account");
        assertFalse(state.shouldHideVideo("video-a"));
        assertTrue(store.getString(key).startsWith("version=2\n"));
        assertFalse(store.getString(key).contains("not-a-valid-state"));

        state.manuallyHideVideo("video-a");
        SubscriptionManagerState reloaded = new SubscriptionManagerState(store);
        reloaded.setAccountIdentifier("account");
        assertTrue(reloaded.shouldHideVideo("video-a"));
    }

    @Test
    public void persistentSwipeHideRequiresResolvedAccountAndSuccessfulWrite() {
        InMemoryStore unresolvedStore = new InMemoryStore();
        SubscriptionManagerState unresolved = new SubscriptionManagerState(unresolvedStore);
        assertFalse(unresolved.manuallyHideVideoPersistently("Abc_def-123", null));
        assertFalse(unresolved.shouldHideVideo("Abc_def-123"));

        SubscriptionManagerPreferences.Store failingStore = new SubscriptionManagerPreferences.Store() {
            @Override public String getString(String key) { return null; }
            @Override public void putString(String key, String value) { throw new RuntimeException("disk"); }
            @Override public void remove(String key) { }
        };
        SubscriptionManagerAccount account = SubscriptionManagerAccount.fromIdentifier("account");
        SubscriptionManagerState resolved = new SubscriptionManagerState(failingStore, account);
        try {
            resolved.manuallyHideVideoPersistently("Abc_def-123", account.getNamespace());
            throw new AssertionError("Expected failed persistence");
        } catch (RuntimeException expected) {
            assertFalse(resolved.shouldHideVideo("Abc_def-123"));
        }
    }

    @Test
    public void persistentSwipeRejectsStaleAccountNamespace() {
        InMemoryStore store = new InMemoryStore();
        SubscriptionManagerAccount first = SubscriptionManagerAccount.fromIdentifier("first");
        SubscriptionManagerAccount second = SubscriptionManagerAccount.fromIdentifier("second");
        SubscriptionManagerState state = new SubscriptionManagerState(store, first);

        state.setAccount(second);

        assertFalse(state.manuallyHideVideoPersistently("Abc_def-123", first.getNamespace()));
        assertFalse(state.shouldHideVideo("Abc_def-123"));
        assertFalse(store.values.containsKey(second.stateKey()));
    }

    @Test
    public void accountSwitchCannotRedirectInFlightSwipeWrite() throws Exception {
        final InMemoryStore delegate = new InMemoryStore();
        final CountDownLatch writeStarted = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);
        SubscriptionManagerPreferences.Store blockingStore = new SubscriptionManagerPreferences.Store() {
            @Override
            public String getString(String key) {
                return delegate.getString(key);
            }

            @Override
            public void putString(String key, String value) {
                writeStarted.countDown();
                try {
                    if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release swipe write");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                delegate.putString(key, value);
            }

            @Override
            public void remove(String key) {
                delegate.remove(key);
            }
        };
        final SubscriptionManagerAccount first =
                SubscriptionManagerAccount.fromIdentifier("first");
        final SubscriptionManagerAccount second =
                SubscriptionManagerAccount.fromIdentifier("second");
        final SubscriptionManagerState state = new SubscriptionManagerState(blockingStore, first);
        final AtomicBoolean persisted = new AtomicBoolean(false);
        Thread swipe = new Thread(() -> persisted.set(state.manuallyHideVideoPersistently(
                "Abc_def-123", first.getNamespace())));
        Thread accountSwitch = new Thread(() -> state.setAccount(second));

        swipe.start();
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
        accountSwitch.start();
        releaseWrite.countDown();
        swipe.join(5000);
        accountSwitch.join(5000);

        assertFalse(swipe.isAlive());
        assertFalse(accountSwitch.isAlive());
        assertTrue(persisted.get());
        assertTrue(delegate.values.containsKey(first.stateKey()));
        assertFalse(delegate.values.containsKey(second.stateKey()));
        assertEquals(second, state.getAccount());
        assertFalse(state.shouldHideVideo("Abc_def-123"));
    }

    @Test
    public void fullManualHideCollectionRejectsAbsentSwipeButAcceptsExistingKey() {
        InMemoryStore store = new InMemoryStore();
        SubscriptionManagerAccount account = SubscriptionManagerAccount.fromIdentifier("full");
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < SubscriptionManagerState.MAX_IDS_PER_COLLECTION; index++) {
            keys.add(SubscriptionManagerHash.identityKey(
                    account.getNamespace(), "video", "video-" + index));
        }
        SubscriptionManagerState.Snapshot full = SubscriptionManagerState.Snapshot.create(
                keys, null, null, null, null);
        store.putString(account.stateKey(), SubscriptionManagerStateCodec.serialize(full));
        SubscriptionManagerState state = new SubscriptionManagerState(store, account);

        assertTrue(state.manuallyHideVideoPersistently("video-0", account.getNamespace()));
        assertFalse(state.manuallyHideVideoPersistently("absent-video", account.getNamespace()));
        assertFalse(state.shouldHideVideo("absent-video"));
    }

    @Test
    public void collectionsAreCappedAndIdsAreValidated() {
        SubscriptionManagerState state = new SubscriptionManagerState(new InMemoryStore());

        assertEquals(1000, SubscriptionManagerState.MAX_IDS_PER_COLLECTION);
        assertEquals(64, SubscriptionManagerState.MAX_ID_LENGTH);
        for (int i = 0; i < SubscriptionManagerState.MAX_IDS_PER_COLLECTION + 10; i++) {
            state.manuallyHideVideo("video-" + i);
        }

        assertEquals(
                SubscriptionManagerState.MAX_IDS_PER_COLLECTION,
                state.snapshot().getManuallyHiddenVideoIds().size()
        );
        assertFalse(state.shouldHideVideo("video-" + (SubscriptionManagerState.MAX_IDS_PER_COLLECTION + 9)));

        try {
            state.manuallyHideVideo(repeat('x', SubscriptionManagerState.MAX_ID_LENGTH + 1));
            throw new AssertionError("Expected invalid id to throw");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void validYouTubeIdentityFormsFitWithinPersistenceLimits() {
        SubscriptionManagerState state = new SubscriptionManagerState(new InMemoryStore());
        String videoId = "Abc_def-123";
        String channelId = "UCabcdefghijklmnopqrstuv";
        String handle = "@creator.handle";

        state.manuallyHideVideo(videoId);
        state.hideChannelId(channelId);
        state.hideChannelHandle(handle);

        assertTrue(state.shouldHideVideo(videoId));
        assertTrue(state.shouldHideChannel(channelId, null));
        assertTrue(state.shouldHideChannel(null, handle));
    }

    @Test
    public void concurrentReadersObserveImmutableSnapshots() throws Exception {
        final SubscriptionManagerState state = new SubscriptionManagerState(new InMemoryStore());
        int workers = 8;
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        final AtomicBoolean failed = new AtomicBoolean(false);
        List<Runnable> tasks = new ArrayList<>();

        for (int i = 0; i < workers / 2; i++) {
            final int worker = i;
            tasks.add(new Runnable() {
                @Override
                public void run() {
                    await(start, failed);
                    for (int index = 0; index < 250; index++) {
                        state.manuallyHideVideo("manual-" + worker + '-' + index);
                        state.markVideoHiddenAsWatched("watched-" + worker + '-' + index);
                        state.restoreWatchedVideo("watched-" + worker + '-' + index);
                    }
                }
            });
        }
        for (int i = 0; i < workers / 2; i++) {
            tasks.add(new Runnable() {
                @Override
                public void run() {
                    await(start, failed);
                    for (int index = 0; index < 1000; index++) {
                        try {
                            state.shouldHideVideo("manual-0-" + index);
                            for (String id : state.snapshot().getManuallyHiddenVideoIds()) {
                                assertFalse(id.isEmpty());
                            }
                        } catch (RuntimeException ex) {
                            failed.set(true);
                        }
                    }
                }
            });
        }

        for (Runnable task : tasks) {
            executor.submit(task);
        }
        start.countDown();
        executor.shutdown();

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertFalse(failed.get());
        assertTrue(state.snapshot().getManuallyHiddenVideoIds().size() > 0);
    }

    private static void await(CountDownLatch latch, AtomicBoolean failed) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            failed.set(true);
            Thread.currentThread().interrupt();
        }
    }

    private static String repeat(char value, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    static final class InMemoryStore implements SubscriptionManagerPreferences.Store {
        final Map<String, String> values = new ConcurrentHashMap<>();

        @Override
        public String getString(String key) {
            return values.get(key);
        }

        @Override
        public void putString(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
