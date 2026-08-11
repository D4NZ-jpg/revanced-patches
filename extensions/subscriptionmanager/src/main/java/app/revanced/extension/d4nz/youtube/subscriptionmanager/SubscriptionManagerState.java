package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe state with immutable, allocation-free snapshots for feed filter reads. */
public final class SubscriptionManagerState {
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_IDS_PER_COLLECTION = 1000;
    static final int STORED_KEY_LENGTH = 64;

    private static final String VIDEO_DOMAIN = "video";
    private static final String CHANNEL_ID_DOMAIN = "channel-id";
    private static final String CHANNEL_HANDLE_DOMAIN = "channel-handle";

    private final SubscriptionManagerPreferences.Store store;
    private final AtomicReference<Snapshot> snapshot;

    private SubscriptionManagerAccount account;

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
        return updateSnapshot(snapshot.get().withAddedManualHiddenVideo(
                identityKey(VIDEO_DOMAIN, requireValidId(videoId))));
    }

    /**
     * Used by swipe: success means the binding still belongs to the current resolved account and
     * the hide is persisted. The namespace check and write share this synchronized boundary with
     * account changes, preventing a stale binding from writing into a newly selected account.
     */
    synchronized boolean manuallyHideVideoPersistently(
            String videoId, String expectedAccountNamespace) {
        if (!account.isPersistent()
                || !account.getNamespace().equals(expectedAccountNamespace)) return false;
        String key = identityKey(VIDEO_DOMAIN, requireValidId(videoId));
        Snapshot current = snapshot.get();
        if (current.manuallyHiddenVideoIds.contains(key)) return true;
        if (current.manuallyHiddenVideoIds.size() >= MAX_IDS_PER_COLLECTION) return false;
        Snapshot next = current.withAddedManualHiddenVideo(key);
        store.putString(account.stateKey(), SubscriptionManagerStateCodec.serialize(next));
        snapshot.set(next);
        return true;
    }

    synchronized String currentPersistentAccountNamespace() {
        return account.isPersistent() ? account.getNamespace() : null;
    }

    public synchronized boolean restoreManuallyHiddenVideo(String videoId) {
        return updateSnapshot(snapshot.get().withRemovedManualHiddenVideo(
                identityKey(VIDEO_DOMAIN, requireValidId(videoId))));
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
