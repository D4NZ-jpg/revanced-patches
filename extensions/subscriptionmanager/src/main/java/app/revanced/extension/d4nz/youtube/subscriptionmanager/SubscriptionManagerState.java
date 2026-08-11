package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe state with immutable, allocation-free snapshots for feed filter reads. */
public final class SubscriptionManagerState {
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_IDS_PER_COLLECTION = 1000;
    static final int STORED_KEY_LENGTH = 64;
    static final int MAX_PENDING_SWIPE_MUTATIONS = 128;

    private static final String VIDEO_DOMAIN = "video";
    private static final String CHANNEL_ID_DOMAIN = "channel-id";
    private static final String CHANNEL_HANDLE_DOMAIN = "channel-handle";

    private final SubscriptionManagerPreferences.Store store;
    private final AtomicReference<Snapshot> snapshot;
    private final LinkedHashMap<SwipeMutationKey, Long> pendingSwipeMutations =
            new LinkedHashMap<>();

    private SubscriptionManagerAccount account;
    private long nextSwipeMutationToken = 1L;

    public SubscriptionManagerState(SubscriptionManagerPreferences.Store store) {
        this(store, SubscriptionManagerAccount.unresolved());
    }

    public SubscriptionManagerState(
            SubscriptionManagerPreferences.Store store,
            SubscriptionManagerAccount initialAccount
    ) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (initialAccount == null) {
            throw new IllegalArgumentException("initialAccount must not be null");
        }
        this.store = store;
        this.account = initialAccount;
        this.snapshot = new AtomicReference<>(loadSnapshot(initialAccount));
    }

    public synchronized void setAccountIdentifier(String accountIdentifier) {
        setAccount(SubscriptionManagerAccount.fromIdentifier(accountIdentifier));
    }

    public synchronized void setUnresolvedAccount() {
        setAccount(SubscriptionManagerAccount.unresolved());
    }

    public synchronized void setIncognito(boolean incognito) {
        if (incognito) {
            setAccount(SubscriptionManagerAccount.incognito());
        } else {
            setAccount(SubscriptionManagerAccount.unresolved());
        }
    }

    public synchronized SubscriptionManagerAccount getAccount() {
        return account;
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public boolean shouldHideVideo(String videoId) {
        return shouldHideVideo(videoId, true);
    }

    /** Applies manual hides regardless of watched filtering; show overrides beat watched hides. */
    public boolean shouldHideVideo(String videoId, boolean hideWatchedVideos) {
        if (!isValidId(videoId)) {
            return false;
        }
        return snapshot.get().shouldHideVideo(identityKey(VIDEO_DOMAIN, videoId), hideWatchedVideos);
    }

    public boolean shouldHideChannel(String channelId, String channelHandle) {
        Snapshot current = snapshot.get();
        boolean hiddenById = isValidId(channelId)
                && current.hiddenChannelIds.contains(identityKey(CHANNEL_ID_DOMAIN, channelId));
        boolean hiddenByHandle = isValidId(channelHandle)
                && current.hiddenChannelHandles.contains(identityKey(CHANNEL_HANDLE_DOMAIN, channelHandle));
        return hiddenById || hiddenByHandle;
    }

    public synchronized boolean manuallyHideVideo(String videoId) {
        String key = identityKey(VIDEO_DOMAIN, requireValidId(videoId));
        boolean changed = updateSnapshot(snapshot.get().withAddedManualHiddenVideo(key));
        pendingSwipeMutations.remove(new SwipeMutationKey(account.getNamespace(), key));
        return changed;
    }

    /**
     * Used by swipe: success means the binding still belongs to the current resolved account and
     * the hide is persisted. The namespace check, ownership token, and write share this
     * synchronized boundary with account changes, preventing stale work from owning a later hide.
     */
    static final int SWIPE_PERSIST_FAILED = 0;
    static final int SWIPE_PERSIST_EXISTING = 1;
    static final int SWIPE_PERSIST_ADDED = 2;

    static final class SwipePersistence {
        static final SwipePersistence FAILED = new SwipePersistence(SWIPE_PERSIST_FAILED, 0L);

        final int status;
        final long mutationToken;

        SwipePersistence(int status, long mutationToken) {
            this.status = status;
            this.mutationToken = mutationToken;
        }
    }

    synchronized boolean manuallyHideVideoPersistently(
            String videoId, String expectedAccountNamespace) {
        return persistManualHideForSwipe(videoId, expectedAccountNamespace).status
                != SWIPE_PERSIST_FAILED;
    }

    synchronized SwipePersistence persistManualHideForSwipe(
            String videoId, String expectedAccountNamespace) {
        if (!account.isPersistent()
                || !account.getNamespace().equals(expectedAccountNamespace)) {
            return SwipePersistence.FAILED;
        }
        String key = identityKey(VIDEO_DOMAIN, requireValidId(videoId));
        SwipeMutationKey mutationKey = new SwipeMutationKey(expectedAccountNamespace, key);
        Snapshot current = snapshot.get();
        if (current.manuallyHiddenVideoIds.contains(key)) {
            Long existingToken = pendingSwipeMutations.get(mutationKey);
            return new SwipePersistence(SWIPE_PERSIST_EXISTING,
                    existingToken == null ? 0L : existingToken);
        }
        if (current.manuallyHiddenVideoIds.size() >= MAX_IDS_PER_COLLECTION) {
            return SwipePersistence.FAILED;
        }
        Snapshot next = current.withAddedManualHiddenVideo(key);
        store.putString(account.stateKey(), SubscriptionManagerStateCodec.serialize(next));
        snapshot.set(next);
        long mutationToken = claimSwipeMutation(mutationKey);
        return new SwipePersistence(SWIPE_PERSIST_ADDED, mutationToken);
    }

    synchronized boolean commitManualHideForSwipe(
            String videoId, String expectedAccountNamespace, long mutationToken) {
        if (mutationToken == 0L || expectedAccountNamespace == null || !isValidId(videoId)) {
            return mutationToken == 0L;
        }
        SwipeMutationKey mutationKey = swipeMutationKey(videoId, expectedAccountNamespace);
        Long owner = pendingSwipeMutations.get(mutationKey);
        if (owner == null || owner != mutationToken) return false;
        pendingSwipeMutations.remove(mutationKey);
        return true;
    }

    synchronized boolean rollbackManualHideForSwipe(
            String videoId, String expectedAccountNamespace, long mutationToken) {
        if (mutationToken == 0L || expectedAccountNamespace == null || !isValidId(videoId)) {
            return false;
        }
        String identityKey = SubscriptionManagerHash.identityKey(
                expectedAccountNamespace, VIDEO_DOMAIN, videoId.trim());
        SwipeMutationKey mutationKey = new SwipeMutationKey(
                expectedAccountNamespace, identityKey);
        Long owner = pendingSwipeMutations.get(mutationKey);
        if (owner == null || owner != mutationToken) return false;

        if (account.isPersistent()
                && account.getNamespace().equals(expectedAccountNamespace)) {
            Snapshot current = snapshot.get();
            if (!current.manuallyHiddenVideoIds.contains(identityKey)) {
                pendingSwipeMutations.remove(mutationKey);
                return true;
            }
            Snapshot next = current.withRemovedManualHiddenVideo(identityKey);
            store.putString(account.stateKey(), SubscriptionManagerStateCodec.serialize(next));
            snapshot.set(next);
            pendingSwipeMutations.remove(mutationKey);
            return true;
        }

        // The account can transition during the settle delay. Roll back the exact captured
        // namespace without applying its snapshot to the newly-active account.
        String stateKey = SubscriptionManagerPreferences.STATE_KEY_PREFIX
                + expectedAccountNamespace;
        String serialized = store.getString(stateKey);
        if (serialized == null || serialized.isEmpty()) {
            pendingSwipeMutations.remove(mutationKey);
            return true;
        }
        SubscriptionManagerStateCodec.DecodeResult decoded =
                SubscriptionManagerStateCodec.decode(serialized);
        if (!decoded.currentFormat) return false;
        if (!decoded.snapshot.manuallyHiddenVideoIds.contains(identityKey)) {
            pendingSwipeMutations.remove(mutationKey);
            return true;
        }
        Snapshot next = decoded.snapshot.withRemovedManualHiddenVideo(identityKey);
        store.putString(stateKey, SubscriptionManagerStateCodec.serialize(next));
        pendingSwipeMutations.remove(mutationKey);
        return true;
    }

    private long claimSwipeMutation(SwipeMutationKey mutationKey) {
        if (!pendingSwipeMutations.containsKey(mutationKey)
                && pendingSwipeMutations.size() >= MAX_PENDING_SWIPE_MUTATIONS) {
            pendingSwipeMutations.remove(pendingSwipeMutations.keySet().iterator().next());
        }
        long mutationToken = nextSwipeMutationToken++;
        if (mutationToken == 0L) mutationToken = nextSwipeMutationToken++;
        pendingSwipeMutations.put(mutationKey, mutationToken);
        return mutationToken;
    }

    private static SwipeMutationKey swipeMutationKey(
            String videoId, String expectedAccountNamespace) {
        return new SwipeMutationKey(expectedAccountNamespace,
                SubscriptionManagerHash.identityKey(
                        expectedAccountNamespace, VIDEO_DOMAIN, videoId.trim()));
    }

    synchronized int pendingSwipeMutationCountForTests() {
        return pendingSwipeMutations.size();
    }

    synchronized String currentPersistentAccountNamespace() {
        return account.isPersistent() ? account.getNamespace() : null;
    }

    synchronized boolean isVideoManuallyHidden(
            String videoId, String expectedAccountNamespace) {
        if (!account.isPersistent()
                || !account.getNamespace().equals(expectedAccountNamespace)
                || !isValidId(videoId)) return false;
        return snapshot.get().shouldHideVideo(identityKey(VIDEO_DOMAIN, videoId), false);
    }

    public synchronized boolean restoreManuallyHiddenVideo(String videoId) {
        String key = identityKey(VIDEO_DOMAIN, requireValidId(videoId));
        boolean changed = updateSnapshot(snapshot.get().withRemovedManualHiddenVideo(key));
        pendingSwipeMutations.remove(new SwipeMutationKey(account.getNamespace(), key));
        return changed;
    }

    public synchronized boolean markVideoHiddenAsWatched(String videoId) {
        return updateSnapshot(snapshot.get().withAddedWatchedHiddenVideo(
                identityKey(VIDEO_DOMAIN, requireValidId(videoId))));
    }

    public synchronized boolean restoreWatchedVideo(String videoId) {
        return updateSnapshot(snapshot.get().withAddedShowOverride(
                identityKey(VIDEO_DOMAIN, requireValidId(videoId))));
    }

    public synchronized boolean clearVideoShowOverride(String videoId) {
        return updateSnapshot(snapshot.get().withRemovedShowOverride(
                identityKey(VIDEO_DOMAIN, requireValidId(videoId))));
    }

    public synchronized boolean hideChannelId(String channelId) {
        return updateSnapshot(snapshot.get().withAddedHiddenChannelId(
                identityKey(CHANNEL_ID_DOMAIN, requireValidId(channelId))));
    }

    public synchronized boolean restoreChannelId(String channelId) {
        return updateSnapshot(snapshot.get().withRemovedHiddenChannelId(
                identityKey(CHANNEL_ID_DOMAIN, requireValidId(channelId))));
    }

    public synchronized boolean hideChannelHandle(String channelHandle) {
        return updateSnapshot(snapshot.get().withAddedHiddenChannelHandle(
                identityKey(CHANNEL_HANDLE_DOMAIN, requireValidId(channelHandle))));
    }

    public synchronized boolean restoreChannelHandle(String channelHandle) {
        return updateSnapshot(snapshot.get().withRemovedHiddenChannelHandle(
                identityKey(CHANNEL_HANDLE_DOMAIN, requireValidId(channelHandle))));
    }

    synchronized void setAccount(SubscriptionManagerAccount nextAccount) {
        if (account.equals(nextAccount)) {
            return;
        }
        Snapshot nextSnapshot = loadSnapshot(nextAccount);
        account = nextAccount;
        snapshot.set(nextSnapshot);
    }

    private Snapshot loadSnapshot(SubscriptionManagerAccount account) {
        if (!account.isPersistent()) return Snapshot.empty();
        String serialized = store.getString(account.stateKey());
        if (serialized == null || serialized.isEmpty()) return Snapshot.empty();
        SubscriptionManagerStateCodec.DecodeResult decoded =
                SubscriptionManagerStateCodec.decode(serialized);
        if (!decoded.currentFormat) {
            // Decode errors fail open, while store failures propagate so account application retries.
            store.putString(account.stateKey(),
                    SubscriptionManagerStateCodec.serialize(Snapshot.empty()));
        }
        return decoded.snapshot;
    }

    private boolean updateSnapshot(Snapshot nextSnapshot) {
        Snapshot previousSnapshot = snapshot.get();
        if (previousSnapshot.equals(nextSnapshot)) return false;
        if (account.isPersistent()) {
            store.putString(account.stateKey(), SubscriptionManagerStateCodec.serialize(nextSnapshot));
        }
        snapshot.set(nextSnapshot);
        return true;
    }

    private String identityKey(String domain, String rawIdentity) {
        return SubscriptionManagerHash.identityKey(account.getNamespace(), domain, rawIdentity.trim());
    }

    private static final class SwipeMutationKey {
        final String accountNamespace;
        final String identityKey;

        SwipeMutationKey(String accountNamespace, String identityKey) {
            this.accountNamespace = accountNamespace;
            this.identityKey = identityKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SwipeMutationKey)) return false;
            SwipeMutationKey key = (SwipeMutationKey) other;
            return accountNamespace.equals(key.accountNamespace)
                    && identityKey.equals(key.identityKey);
        }

        @Override
        public int hashCode() {
            return 31 * accountNamespace.hashCode() + identityKey.hashCode();
        }
    }

    static boolean isValidId(String id) {
        if (id == null) {
            return false;
        }
        String trimmedId = id.trim();
        if (trimmedId.isEmpty() || trimmedId.length() > MAX_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmedId.length(); i++) {
            if (Character.isISOControl(trimmedId.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean isValidStoredKey(String key) {
        if (key == null || key.length() != STORED_KEY_LENGTH) return false;
        for (int i = 0; i < key.length(); i++) {
            char character = key.charAt(i);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) return false;
        }
        return true;
    }

    static String requireValidId(String id) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("invalid subscription manager id");
        }
        return id.trim();
    }

    static Set<String> immutableValidatedSet(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }

        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        for (String id : ids) {
            if (!isValidStoredKey(id)) continue;
            normalizedIds.add(id);
            if (normalizedIds.size() >= MAX_IDS_PER_COLLECTION) {
                break;
            }
        }
        if (normalizedIds.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(normalizedIds);
    }

    private static Set<String> add(Set<String> set, String value) {
        if (set.contains(value) || set.size() >= MAX_IDS_PER_COLLECTION) {
            return set;
        }
        LinkedHashSet<String> next = new LinkedHashSet<>(set);
        next.add(value);
        return Collections.unmodifiableSet(next);
    }

    private static Set<String> remove(Set<String> set, String value) {
        if (!set.contains(value)) {
            return set;
        }
        LinkedHashSet<String> next = new LinkedHashSet<>(set);
        next.remove(value);
        if (next.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(next);
    }

    public static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet()
        );

        private final Set<String> manuallyHiddenVideoIds;
        private final Set<String> watchedHiddenVideoIds;
        private final Set<String> videoShowOverrideIds;
        private final Set<String> hiddenChannelIds;
        private final Set<String> hiddenChannelHandles;

        private Snapshot(
                Set<String> manuallyHiddenVideoIds,
                Set<String> watchedHiddenVideoIds,
                Set<String> videoShowOverrideIds,
                Set<String> hiddenChannelIds,
                Set<String> hiddenChannelHandles
        ) {
            this.manuallyHiddenVideoIds = manuallyHiddenVideoIds;
            this.watchedHiddenVideoIds = watchedHiddenVideoIds;
            this.videoShowOverrideIds = videoShowOverrideIds;
            this.hiddenChannelIds = hiddenChannelIds;
            this.hiddenChannelHandles = hiddenChannelHandles;
        }

        public static Snapshot empty() {
            return EMPTY;
        }

        public static Snapshot create(
                Collection<String> manuallyHiddenVideoIds,
                Collection<String> watchedHiddenVideoIds,
                Collection<String> videoShowOverrideIds,
                Collection<String> hiddenChannelIds,
                Collection<String> hiddenChannelHandles
        ) {
            return new Snapshot(
                    immutableValidatedSet(manuallyHiddenVideoIds),
                    immutableValidatedSet(watchedHiddenVideoIds),
                    immutableValidatedSet(videoShowOverrideIds),
                    immutableValidatedSet(hiddenChannelIds),
                    immutableValidatedSet(hiddenChannelHandles)
            );
        }

        public Set<String> getManuallyHiddenVideoIds() {
            return manuallyHiddenVideoIds;
        }

        public Set<String> getWatchedHiddenVideoIds() {
            return watchedHiddenVideoIds;
        }

        public Set<String> getVideoShowOverrideIds() {
            return videoShowOverrideIds;
        }

        public Set<String> getHiddenChannelIds() {
            return hiddenChannelIds;
        }

        public Set<String> getHiddenChannelHandles() {
            return hiddenChannelHandles;
        }

        public boolean shouldHideVideo(String videoId) {
            return shouldHideVideo(videoId, true);
        }

        public boolean shouldHideVideo(String videoId, boolean hideWatchedVideos) {
            if (manuallyHiddenVideoIds.contains(videoId)) {
                return true;
            }
            if (videoShowOverrideIds.contains(videoId)) {
                return false;
            }
            return hideWatchedVideos && watchedHiddenVideoIds.contains(videoId);
        }

        Snapshot withAddedManualHiddenVideo(String videoId) {
            return copy(add(manuallyHiddenVideoIds, videoId), watchedHiddenVideoIds, videoShowOverrideIds,
                    hiddenChannelIds, hiddenChannelHandles);
        }

        Snapshot withRemovedManualHiddenVideo(String videoId) {
            return copy(remove(manuallyHiddenVideoIds, videoId), watchedHiddenVideoIds, videoShowOverrideIds,
                    hiddenChannelIds, hiddenChannelHandles);
        }

        Snapshot withAddedWatchedHiddenVideo(String videoId) {
            return copy(manuallyHiddenVideoIds, add(watchedHiddenVideoIds, videoId), videoShowOverrideIds,
                    hiddenChannelIds, hiddenChannelHandles);
        }

        Snapshot withAddedShowOverride(String videoId) {
            return copy(manuallyHiddenVideoIds, watchedHiddenVideoIds, add(videoShowOverrideIds, videoId),
                    hiddenChannelIds, hiddenChannelHandles);
        }

        Snapshot withRemovedShowOverride(String videoId) {
            return copy(manuallyHiddenVideoIds, watchedHiddenVideoIds, remove(videoShowOverrideIds, videoId),
                    hiddenChannelIds, hiddenChannelHandles);
        }

        Snapshot withAddedHiddenChannelId(String channelId) {
            return copy(manuallyHiddenVideoIds, watchedHiddenVideoIds, videoShowOverrideIds,
                    add(hiddenChannelIds, channelId), hiddenChannelHandles);
        }

        Snapshot withRemovedHiddenChannelId(String channelId) {
            return copy(manuallyHiddenVideoIds, watchedHiddenVideoIds, videoShowOverrideIds,
                    remove(hiddenChannelIds, channelId), hiddenChannelHandles);
        }

        Snapshot withAddedHiddenChannelHandle(String channelHandle) {
            return copy(manuallyHiddenVideoIds, watchedHiddenVideoIds, videoShowOverrideIds,
                    hiddenChannelIds, add(hiddenChannelHandles, channelHandle));
        }

        Snapshot withRemovedHiddenChannelHandle(String channelHandle) {
            return copy(manuallyHiddenVideoIds, watchedHiddenVideoIds, videoShowOverrideIds,
                    hiddenChannelIds, remove(hiddenChannelHandles, channelHandle));
        }

        private Snapshot copy(
                Set<String> manuallyHiddenVideoIds,
                Set<String> watchedHiddenVideoIds,
                Set<String> videoShowOverrideIds,
                Set<String> hiddenChannelIds,
                Set<String> hiddenChannelHandles
        ) {
            if (this.manuallyHiddenVideoIds == manuallyHiddenVideoIds
                    && this.watchedHiddenVideoIds == watchedHiddenVideoIds
                    && this.videoShowOverrideIds == videoShowOverrideIds
                    && this.hiddenChannelIds == hiddenChannelIds
                    && this.hiddenChannelHandles == hiddenChannelHandles) {
                return this;
            }
            return new Snapshot(manuallyHiddenVideoIds, watchedHiddenVideoIds, videoShowOverrideIds,
                    hiddenChannelIds, hiddenChannelHandles);
        }

        List<Set<String>> collectionsInSerializationOrder() {
            return Arrays.asList(
                    manuallyHiddenVideoIds,
                    watchedHiddenVideoIds,
                    videoShowOverrideIds,
                    hiddenChannelIds,
                    hiddenChannelHandles
            );
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Snapshot)) {
                return false;
            }
            Snapshot that = (Snapshot) other;
            return manuallyHiddenVideoIds.equals(that.manuallyHiddenVideoIds)
                    && watchedHiddenVideoIds.equals(that.watchedHiddenVideoIds)
                    && videoShowOverrideIds.equals(that.videoShowOverrideIds)
                    && hiddenChannelIds.equals(that.hiddenChannelIds)
                    && hiddenChannelHandles.equals(that.hiddenChannelHandles);
        }

        @Override
        public int hashCode() {
            int result = manuallyHiddenVideoIds.hashCode();
            result = 31 * result + watchedHiddenVideoIds.hashCode();
            result = 31 * result + videoShowOverrideIds.hashCode();
            result = 31 * result + hiddenChannelIds.hashCode();
            result = 31 * result + hiddenChannelHandles.hashCode();
            return result;
        }
    }
}
