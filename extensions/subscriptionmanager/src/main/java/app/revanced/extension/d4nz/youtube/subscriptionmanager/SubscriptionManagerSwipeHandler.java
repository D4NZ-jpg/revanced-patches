package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import android.os.Looper;
import android.support.v7.widget.RecyclerView;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.youtube.shared.NavigationBar.NavigationButton;

/** Runtime-gated swipe ownership and gesture bridge for the verified YouTube 20.40.45 bind route. */
public final class SubscriptionManagerSwipeHandler {
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAX_PARENT_DEPTH = 16;
    private static final float MIN_SWIPE_ACTIVATION_DP = 20f;
    private static final float MIN_SWIPE_COMMIT_DP = 64f;
    private static final float SWIPE_COMMIT_WIDTH_FRACTION = 0.22f;
    private static final float HORIZONTAL_DOMINANCE = 1.75f;
    private static final long SWIPE_SETTLE_DURATION_MS = 160L;
    private static final long SWIPE_RETURN_DURATION_MS = 120L;

    private static final Object LOCK = new Object();
    private static final WeakHashMap<View, RootOwnership> ROOTS = new WeakHashMap<>();
    private static final WeakHashMap<View, Binding> ITEMS = new WeakHashMap<>();
    private static final WeakHashMap<View, Presentation> PRESENTATIONS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> DETACH_LISTENERS = new WeakHashMap<>();
    private static final WeakHashMap<RecyclerView, RecyclerTouchListener> RECYCLERS =
            new WeakHashMap<>();
    private static final SubscriptionManagerSwipeVersion VERSIONS =
            new SubscriptionManagerSwipeVersion();

    private SubscriptionManagerSwipeHandler() {
    }

    @SuppressWarnings("unused")
    public static void onLithoComponentBound(Object component, Object rootCandidate) {
        try {
            if (!(rootCandidate instanceof View)) return;
            View root = (View) rootCandidate;
            clearPreviousRootOwnership(root);
            if (!isSwipeContextEnabled()) return;

            final SubscriptionManagerSwipeVersion.Token version;
            synchronized (LOCK) {
                version = VERSIONS.next();
                ROOTS.put(root, new RootOwnership(version));
            }
            if (!ensureDetachListener(root)) {
                discardPending(root, version);
                return;
            }

            // Parentage and the holder position can settle after the bind call. Publishing always
            // resolves identity from the current authoritative source item, never from recycled UI.

            WeakReference<View> rootReference = new WeakReference<>(root);
            try {
                if (!root.post(new PublishRunnable(rootReference, version))) {
                    discardPending(root, version);
                }
            } catch (Throwable ignored) {
                discardPending(root, version);
            }
        } catch (Throwable ignored) {
            // Injected boundary is deliberately fail-open.
        }
    }

    public static void invalidateAllOwnership() {
        try {
            synchronized (LOCK) {
                VERSIONS.invalidateAll();
                ROOTS.clear();
                ITEMS.clear();
                for (Map.Entry<View, Presentation> entry : PRESENTATIONS.entrySet()) {
                    restorePresentation(entry.getKey(), entry.getValue());
                }
                PRESENTATIONS.clear();
                for (RecyclerTouchListener listener : RECYCLERS.values()) listener.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void publishPending(
            View root, SubscriptionManagerSwipeVersion.Token version) {
        synchronized (LOCK) {
            RootOwnership ownership = ROOTS.get(root);
            if (ownership == null || !ownership.matches(version)) return;
        }
        ItemRoute route = findDirectRecyclerItem(root);
        if (route == null) {
            discardPending(root, version);
            return;
        }
        if (!isSwipeContextEnabled()) {
            discardPending(root, version);
            return;
        }
        RemovalPlan plan = attestSourceRemoval(route.recyclerView, route.item);
        if (plan == null) {
            discardPending(root, version);
            return;
        }
        String videoId = plan.videoId;
        String accountNamespace = SubscriptionManager.currentPersistentAccountNamespaceForSwipe();
        if (accountNamespace == null) {
            discardPending(root, version);
            return;
        }
        boolean alreadyHidden = SubscriptionManager.isVideoManuallyHiddenForSwipe(
                videoId, accountNamespace);

        synchronized (LOCK) {
            RootOwnership ownership = ROOTS.get(root);
            if (ownership == null || !ownership.matches(version)) return;
            RecyclerTouchListener listener = RECYCLERS.get(route.recyclerView);
            if (listener == null) {
                listener = new RecyclerTouchListener(route.recyclerView);
                if (!attachItemTouchListener(route.recyclerView, listener)) {
                    discardPending(root, version);
                    return;
                }
                RECYCLERS.put(route.recyclerView, listener);
            }

            Presentation oldPresentation = PRESENTATIONS.remove(route.item);
            restorePresentation(route.item, oldPresentation);
            Binding old = ITEMS.remove(route.item);
            if (old != null) {
                View oldRoot = old.root();
                if (oldRoot != null) ROOTS.remove(oldRoot);
                for (RecyclerTouchListener existingListener : RECYCLERS.values()) {
                    if (existingListener.references(route.item)) existingListener.cancel();
                }
            }
            if (!ensureDetachListener(route.item)) {
                discardPending(root, version);
                return;
            }
            PRESENTATIONS.put(route.item, new Presentation(route.item));
            ownership.item = new WeakReference<>(route.item);
            if (alreadyHidden) {
                SubscriptionManagerState.SwipePersistence persistence =
                        SubscriptionManager.persistManualHideForSwipe(
                                videoId, accountNamespace);
                if (persistence.status != SubscriptionManagerState.SWIPE_PERSIST_FAILED
                        && executeSourceRemoval(plan)) {
                    return;
                }
                // Keep a normal binding when reapplying a persisted hide fails. The visible row
                // remains armed so a later swipe can retry the strictly attested source mutation.
            }
            Binding binding = new Binding(root, route.item, route.recyclerView,
                    videoId, accountNamespace, version);
            ITEMS.put(route.item, binding);
        }
    }

    private static boolean attachItemTouchListener(
            RecyclerView recyclerView, RecyclerTouchListener listener) {
        try {
            ClassLoader classLoader = recyclerView.getClass().getClassLoader();
            Class<?> listenerClass = Class.forName("nj", false, classLoader);
            if (!listenerClass.isInterface()) return false;
            Method addListener = recyclerView.getClass().getMethod("y", listenerClass);
            if (addListener.getReturnType() != Void.TYPE) return false;
            Object proxy = Proxy.newProxyInstance(
                    classLoader, new Class<?>[]{listenerClass}, listener);
            addListener.invoke(recyclerView, proxy);
            listener.proxy = proxy;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static RemovalPlan attestSourceRemoval(RecyclerView recyclerView, View item) {
        try {
            if (recyclerView == null || item == null || !item.isAttachedToWindow()) return null;

            Field adapterField = RecyclerView.class.getField("k");
            if (Modifier.isStatic(adapterField.getModifiers())) return null;
            Object adapter = adapterField.get(recyclerView);
            if (!isExactNamed(adapter, "gnw")) return null;
            Object owner = readAttestedField(adapter, "gnw", "a", "gnz");
            Object adapterSource = readAttestedField(owner, "gnz", "d", "ansr");
            Object presenter = readAttestedField(adapterSource, "ansr", "b", "anhc");
            Object source = readAttestedField(presenter, "anhc", "f", null);
            if (source == null
                    || !isExactNamed(source, "anhg") && !isExactNamed(source, "ange")) return null;

            Method holderMethod = RecyclerView.class.getMethod("m", View.class);
            if (!Modifier.isStatic(holderMethod.getModifiers())
                    || holderMethod.getReturnType() == Void.TYPE) return null;
            Object holder = holderMethod.invoke(null, item);
            Method positionMethod = holderPositionMethod(holder);
            int globalPosition = (Integer) positionMethod.invoke(holder);

            Method adapterCountMethod = exactInstanceMethod(adapter, "gnw", "a", Integer.TYPE);
            Method sourceCountMethod = exactInstanceMethod(
                    source, source.getClass().getName(), "a", Integer.TYPE);
            Method sourceItemMethod = exactInstanceMethod(
                    source, source.getClass().getName(), "c", Object.class, Integer.TYPE);
            int adapterCount = (Integer) adapterCountMethod.invoke(adapter);
            int sourceCount = (Integer) sourceCountMethod.invoke(source);

            Object leaf;
            int localPosition;
            if (isExactNamed(source, "anhg")) {
                leaf = source;
                localPosition = globalPosition;
            } else if (isExactNamed(source, "ange")) {
                Method childRouteMethod = exactInstanceMethod(
                        source, "ange", "l", namedClass(source, "angd"), Integer.TYPE);
                Object childRoute = childRouteMethod.invoke(source, globalPosition);
                if (!isExactNamed(childRoute, "angd")) return null;
                leaf = readAttestedField(childRoute, "angd", "a", "anhg");
                Method localPositionMethod = exactInstanceMethod(
                        childRoute, "angd", "f", Integer.TYPE, Integer.TYPE);
                localPosition = (Integer) localPositionMethod.invoke(childRoute, globalPosition);
            } else {
                return null;
            }

            Method leafCountMethod = exactInstanceMethod(leaf, "anhg", "a", Integer.TYPE);
            Method leafItemMethod = exactInstanceMethod(
                    leaf, "anhg", "c", Object.class, Integer.TYPE);
            Method removeMethod = exactInstanceMethod(
                    leaf, "anhg", "i", Void.TYPE, Integer.TYPE, Integer.TYPE);
            int leafCount = (Integer) leafCountMethod.invoke(leaf);
            if (!attestCounts(adapterCount, sourceCount, leafCount,
                    globalPosition, localPosition)) return null;
            Object sourceItem = sourceItemMethod.invoke(source, globalPosition);
            Object leafItem = leafItemMethod.invoke(leaf, localPosition);
            int confirmedPosition = (Integer) positionMethod.invoke(holder);
            if (sourceItem == null || sourceItem != leafItem
                    || confirmedPosition != globalPosition) return null;
            String videoId = extractSourceItemVideoId(sourceItem);
            if (videoId == null) return null;
            return new RemovalPlan(recyclerView, item, adapter, owner, adapterSource, presenter,
                    source, leaf, sourceItem, videoId, holder, positionMethod, adapterCountMethod,
                    sourceCountMethod, sourceItemMethod, leafCountMethod, leafItemMethod,
                    removeMethod, globalPosition, localPosition, adapterCount, sourceCount,
                    leafCount);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean executeSourceRemoval(RemovalPlan plan) {
        if (plan == null) return false;
        boolean mutationStarted = false;
        try {
            RecyclerView recyclerView = plan.recyclerView.get();
            View item = plan.item.get();
            if (recyclerView == null || item == null || !item.isAttachedToWindow()) return false;
            Field adapterField = RecyclerView.class.getField("k");
            if (Modifier.isStatic(adapterField.getModifiers())
                    || adapterField.get(recyclerView) != plan.adapter) return false;
            if (readAttestedField(plan.adapter, "gnw", "a", "gnz") != plan.owner
                    || readAttestedField(plan.owner, "gnz", "d", "ansr") != plan.adapterSource
                    || readAttestedField(plan.adapterSource, "ansr", "b", "anhc") != plan.presenter
                    || readAttestedField(plan.presenter, "anhc", "f", null) != plan.source) {
                return false;
            }
            Object currentHolder = RecyclerView.class.getMethod("m", View.class).invoke(null, item);
            if (currentHolder != plan.holder
                    || (Integer) plan.positionMethod.invoke(currentHolder) != plan.globalPosition) {
                return false;
            }
            int adapterCount = (Integer) plan.adapterCountMethod.invoke(plan.adapter);
            int sourceCount = (Integer) plan.sourceCountMethod.invoke(plan.source);
            int leafCount = (Integer) plan.leafCountMethod.invoke(plan.leaf);
            if (adapterCount != plan.adapterCount || sourceCount != plan.sourceCount
                    || leafCount != plan.leafCount
                    || !attestCounts(adapterCount, sourceCount, leafCount,
                            plan.globalPosition, plan.localPosition)
                    || plan.sourceItemMethod.invoke(plan.source, plan.globalPosition)
                            != plan.sourceItem
                    || plan.leafItemMethod.invoke(plan.leaf, plan.localPosition)
                            != plan.sourceItem) return false;

            mutationStarted = true;
            plan.removeMethod.invoke(plan.leaf, plan.localPosition, 1);
            return removalPostcondition(plan);
        } catch (Throwable ignored) {
            if (mutationStarted) {
                try {
                    if (removalPostcondition(plan)) return true;
                } catch (Throwable ignoredPostcondition) {
                }
            }
            return false;
        }
    }

    private static boolean removalPostcondition(RemovalPlan plan) throws Exception {
        int remainingAdapterCount = (Integer) plan.adapterCountMethod.invoke(plan.adapter);
        int remainingSourceCount = (Integer) plan.sourceCountMethod.invoke(plan.source);
        int remainingLeafCount = (Integer) plan.leafCountMethod.invoke(plan.leaf);
        return removalPostconditionCounts(plan.adapterCount, plan.sourceCount, plan.leafCount,
                remainingAdapterCount, remainingSourceCount, remainingLeafCount);
    }

    static boolean removalPostconditionCounts(
            int adapterCount, int sourceCount, int leafCount,
            int remainingAdapterCount, int remainingSourceCount, int remainingLeafCount) {
        return remainingAdapterCount == adapterCount - 1
                && remainingSourceCount == sourceCount - 1
                && remainingLeafCount == leafCount - 1;
    }

    static boolean shouldRequestPersistedHideRebind(
            boolean bindingCurrent,
            boolean originalRemovalSucceeded,
            boolean refreshedRemovalSucceeded) {
        return bindingCurrent && !originalRemovalSucceeded && !refreshedRemovalSucceeded;
    }

    static boolean shouldRestorePersistedHidePresentation(
            boolean rebindRequired, boolean rebindSucceeded) {
        return rebindRequired && !rebindSucceeded;
    }

    static boolean attestCounts(
            int adapterCount, int sourceCount, int leafCount,
            int globalPosition, int localPosition) {
        return adapterCount >= 0 && sourceCount == adapterCount
                && globalPosition >= 0 && globalPosition < sourceCount
                && leafCount > 0 && leafCount <= sourceCount
                && localPosition >= 0 && localPosition < leafCount;
    }

    private static Object readAttestedField(
            Object owner, String ownerClass, String fieldName, String valueClass) throws Exception {
        if (!isExactNamed(owner, ownerClass)) return null;
        Field field = owner.getClass().getDeclaredField(fieldName);
        if (Modifier.isStatic(field.getModifiers())) return null;
        if (!field.isAccessible()) field.setAccessible(true);
        Object value = field.get(owner);
        return valueClass == null || isExactNamed(value, valueClass) ? value : null;
    }

    private static Method exactInstanceMethod(
            Object owner, String ownerClass, String methodName, Class<?> returnType,
            Class<?>... parameterTypes) throws Exception {
        if (!isExactNamed(owner, ownerClass)) throw new NoSuchMethodException(methodName);
        Method method = owner.getClass().getDeclaredMethod(methodName, parameterTypes);
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != returnType) {
            throw new NoSuchMethodException(methodName);
        }
        if (!method.isAccessible()) method.setAccessible(true);
        return method;
    }

    static Method holderPositionMethod(Object holder) throws Exception {
        Class<?> nvDeclaration = namedClassInHierarchy(holder, "nv");
        if (nvDeclaration == null) throw new NoSuchMethodException("b");
        Method method = nvDeclaration.getDeclaredMethod("b");
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Integer.TYPE) {
            throw new NoSuchMethodException("b");
        }
        if (!method.isAccessible()) method.setAccessible(true);
        return method;
    }

    private static Method adapterNotifyItemChangedMethod(Object adapter) throws Exception {
        Class<?> mxDeclaration = namedClassInHierarchy(adapter, "mx");
        if (mxDeclaration == null) throw new NoSuchMethodException("hf");
        Method method = mxDeclaration.getDeclaredMethod("hf", Integer.TYPE);
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers)
                || !Modifier.isFinal(modifiers) || method.getReturnType() != Void.TYPE) {
            throw new NoSuchMethodException("hf");
        }
        if (!method.isAccessible()) method.setAccessible(true);
        return method;
    }

    private static Class<?> namedClassInHierarchy(Object value, String className) {
        if (value == null) return null;
        Class<?> type = value.getClass();
        while (type != null && type != Object.class) {
            if (className.equals(type.getName())) return type;
            type = type.getSuperclass();
        }
        return null;
    }

    private static Class<?> namedClass(Object anchor, String className)
            throws ClassNotFoundException {
        return Class.forName(className, false, anchor.getClass().getClassLoader());
    }

    private static boolean isExactNamed(Object value, String className) {
        return value != null && className.equals(value.getClass().getName());
    }

    private static boolean ensureDetachListener(View view) {
        synchronized (LOCK) {
            if (DETACH_LISTENERS.containsKey(view)) return true;
            try {
                view.addOnAttachStateChangeListener(new OwnershipDetachListener(view));
                DETACH_LISTENERS.put(view, Boolean.TRUE);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static void discardPending(
            View root, SubscriptionManagerSwipeVersion.Token version) {
        synchronized (LOCK) {
            RootOwnership ownership = ROOTS.get(root);
            if (ownership != null && ownership.matches(version)) ROOTS.remove(root);
        }
    }

    private static void clearPreviousRootOwnership(View root) {
        synchronized (LOCK) {
            RootOwnership previous = ROOTS.remove(root);
            if (previous == null) return;
            View item = previous.item();
            if (item == null) return;
            Binding binding = ITEMS.get(item);
            if (binding != null && binding.version == previous.version) ITEMS.remove(item);
            Presentation presentation = PRESENTATIONS.remove(item);
            restorePresentation(item, presentation);
            for (RecyclerTouchListener listener : RECYCLERS.values()) {
                if (listener.references(item)) listener.cancel();
            }
        }
    }

    private static void detach(View item) {
        synchronized (LOCK) {
            DETACH_LISTENERS.remove(item);
            Binding binding = ITEMS.remove(item);
            if (binding != null) {
                View root = binding.root();
                RootOwnership ownership = root == null ? null : ROOTS.get(root);
                if (ownership != null && ownership.version == binding.version) ROOTS.remove(root);
            }
            Presentation presentation = PRESENTATIONS.remove(item);
            restorePresentation(item, presentation);
            for (RecyclerTouchListener listener : RECYCLERS.values()) {
                if (listener.references(item)) listener.cancel();
            }
        }
    }

    private static Binding findBindingAt(RecyclerView recyclerView, float x, float y) {
        try {
            View item = recyclerView.o(x, y);
            if (item == null) return null;
            synchronized (LOCK) {
                Binding binding = ITEMS.get(item);
                return binding != null && binding.recyclerView() == recyclerView
                        && VERSIONS.isCurrent(binding.version)
                        && item.getVisibility() == View.VISIBLE ? binding : null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isCurrent(Binding binding) {
        synchronized (LOCK) {
            View item = binding == null ? null : binding.item();
            return item != null && VERSIONS.isCurrent(binding.version)
                    && ITEMS.get(item) == binding;
        }
    }

    private static boolean completeSwipe(final Binding binding, MotionEvent event) {
        final View item = binding == null ? null : binding.item();
        final RecyclerView recyclerView = binding == null ? null : binding.recyclerView();
        final RemovalPlan plan = attestSourceRemoval(recyclerView, item);
        if (plan == null || !plan.videoId.equals(binding.videoId)) return false;
        final RemovalAttempt attempt = new RemovalAttempt(binding, plan);
        synchronized (LOCK) {
            View root = binding.root();
            RootOwnership ownership = root == null ? null : ROOTS.get(root);
            if (item == null || ITEMS.get(item) != binding
                    || !VERSIONS.isCurrent(binding.version)
                    || ownership == null || ownership.version != binding.version
                    || ownership.item() != item || !isSwipeContextEnabled()
                    || item.getHandler() == null
                    || Looper.myLooper() != item.getHandler().getLooper()) return false;
            try {
                if (!item.postDelayed(attempt, SWIPE_SETTLE_DURATION_MS)) return false;
            } catch (Throwable ignored) {
                return false;
            }
            SubscriptionManagerState.SwipePersistence persistence =
                    SubscriptionManager.persistManualHideForSwipe(
                            binding.videoId, binding.accountNamespace);
            if (persistence.status == SubscriptionManagerState.SWIPE_PERSIST_FAILED) {
                item.removeCallbacks(attempt);
                return false;
            }
            attempt.persistenceReady = true;
        }
        // Native Hide is best-effort. The existing persistent local removal remains authoritative
        // and runs after the animation if YouTube rejects, delays, or changes the native route.
        SubscriptionManagerNativeHide.tryHide(item, event);
        try {
            item.animate().cancel();
            item.animate()
                    .translationX(-Math.max(item.getWidth(), 1))
                    .alpha(0f)
                    .setDuration(SWIPE_SETTLE_DURATION_MS)
                    .start();
            return true;
        } catch (Throwable failure) {
            item.removeCallbacks(attempt);
            if (!executeSourceRemoval(plan)
                    && !requestPersistedHideRebind(binding)) {
                restoreBindingPresentation(binding);
            }
            return true;
        }
    }

    private static void updateDrag(Binding binding, float offset) {
        try {
            View item = binding == null ? null : binding.item();
            if (item == null || !isCurrent(binding)) return;
            float translation = Math.min(0f, offset);
            float width = Math.max(item.getWidth(), 1);
            float progress = Math.min(1f, -translation / width);
            item.animate().cancel();
            item.setTranslationX(translation);
            item.setAlpha(1f - progress * 0.4f);
        } catch (Throwable ignored) {
        }
    }

    private static void restoreBindingPresentation(Binding binding) {
        View item = binding == null ? null : binding.item();
        if (item == null) return;
        Presentation presentation;
        synchronized (LOCK) {
            presentation = PRESENTATIONS.get(item);
        }
        restorePresentation(item, presentation);
    }

    private static void animateBack(Binding binding) {
        try {
            View item = binding == null ? null : binding.item();
            if (item == null) return;
            Presentation presentation;
            synchronized (LOCK) {
                presentation = PRESENTATIONS.get(item);
            }
            float translation = presentation == null ? 0f : presentation.previousTranslationX;
            float alpha = presentation == null ? 1f : presentation.previousAlpha;
            item.animate().cancel();
            item.animate()
                    .translationX(translation)
                    .alpha(alpha)
                    .setDuration(SWIPE_RETURN_DURATION_MS)
                    .start();
        } catch (Throwable ignored) {
        }
    }

    private static boolean requestPersistedHideRebind(Binding binding) {
        try {
            View item = binding == null ? null : binding.item();
            RecyclerView recyclerView = binding == null ? null : binding.recyclerView();
            if (item == null || recyclerView == null || !item.isAttachedToWindow()
                    || !isCurrent(binding)) return false;
            Field adapterField = RecyclerView.class.getField("k");
            if (Modifier.isStatic(adapterField.getModifiers())) return false;
            Object adapter = adapterField.get(recyclerView);
            if (!isExactNamed(adapter, "gnw")) return false;
            Object holder = RecyclerView.class.getMethod("m", View.class).invoke(null, item);
            int position = (Integer) holderPositionMethod(holder).invoke(holder);
            Method countMethod = exactInstanceMethod(adapter, "gnw", "a", Integer.TYPE);
            int count = (Integer) countMethod.invoke(adapter);
            if (position < 0 || position >= count) return false;
            adapterNotifyItemChangedMethod(adapter).invoke(adapter, position);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isSwipeContextEnabled() {
        return isSwipeContextEnabled(
                Boolean.TRUE.equals(
                        SubscriptionManagerSettings.SUBSCRIPTION_MANAGER_SWIPE_TO_HIDE.get()),
                Boolean.TRUE.equals(SubscriptionManagerSettings.SUBSCRIPTION_MANAGER.get()),
                NavigationButton.getSelectedNavigationButton());
    }

    static boolean isSwipeContextEnabled(
            boolean swipeEnabled, boolean managerEnabled, NavigationButton selected) {
        return swipeEnabled && managerEnabled && selected == NavigationButton.SUBSCRIPTIONS;
    }

    private static void restorePresentation(View item, Presentation presentation) {
        if (item == null || presentation == null) return;
        try {
            ViewGroup.LayoutParams layoutParams = item.getLayoutParams();
            if (layoutParams != null && presentation.hadLayoutParams) {
                layoutParams.height = presentation.previousHeight;
                if (layoutParams instanceof ViewGroup.MarginLayoutParams
                        && presentation.hadMargins) {
                    ViewGroup.MarginLayoutParams margins =
                            (ViewGroup.MarginLayoutParams) layoutParams;
                    margins.topMargin = presentation.previousTopMargin;
                    margins.bottomMargin = presentation.previousBottomMargin;
                }
                item.setLayoutParams(layoutParams);
            }
            item.setVisibility(presentation.previousVisibility);
            item.setAlpha(presentation.previousAlpha);
            item.setTranslationX(presentation.previousTranslationX);
            item.requestLayout();
            ViewParent parent = item.getParent();
            if (parent instanceof View) ((View) parent).requestLayout();
        } catch (Throwable ignored) {
        }
    }

    static String extractSourceItemVideoId(Object sourceItem) {
        try {
            if (!isNamed(sourceItem, "amtj") || isNamed(sourceItem, "amvi")) return null;
            Object payload = readFieldFromNamedClass(sourceItem, "amtj", "c");
            return payload instanceof byte[]
                    ? extractEarliestFieldOneVideoId((byte[]) payload) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String extractEarliestFieldOneVideoId(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES) return null;
        for (int offset = 0; offset + 13 <= bytes.length; offset++) {
            if ((bytes[offset] & 0xff) != 0x0a || (bytes[offset + 1] & 0xff) != 11) continue;
            boolean validVideoId = true;
            for (int index = offset + 2; index < offset + 13; index++) {
                if (!isVideoIdCharacter(bytes[index] & 0xff)) {
                    validVideoId = false;
                    break;
                }
            }
            if (validVideoId) {
                return new String(bytes, offset + 2, 11, StandardCharsets.US_ASCII);
            }
        }
        return null;
    }

    private static boolean isVideoIdCharacter(int character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '_' || character == '-';
    }

    private static Object readFieldFromNamedClass(Object owner, String className, String fieldName)
            throws Exception {
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            if (className.equals(type.getName())) {
                Field field = type.getDeclaredField(fieldName);
                if (Modifier.isStatic(field.getModifiers())) return null;
                if (!field.isAccessible()) field.setAccessible(true);
                return field.get(owner);
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static boolean isNamed(Object value, String className) {
        if (value == null) return false;
        Class<?> type = value.getClass();
        while (type != null && type != Object.class) {
            if (className.equals(type.getName())) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    private static ItemRoute findDirectRecyclerItem(View root) {
        View child = root;
        ViewParent parent = root.getParent();
        for (int depth = 0; depth < MAX_PARENT_DEPTH && parent != null; depth++) {
            if (parent instanceof RecyclerView) return new ItemRoute(child, (RecyclerView) parent);
            if (!(parent instanceof View)) return null;
            child = (View) parent;
            parent = child.getParent();
        }
        return null;
    }

    private static final class RecyclerTouchListener implements InvocationHandler {
        private final WeakReference<RecyclerView> recyclerView;
        private final GestureClassifier classifier;
        private final float minimumCommitDistance;
        private Binding active;
        private float downX;
        private Object proxy;

        RecyclerTouchListener(RecyclerView recyclerView) {
            this.recyclerView = new WeakReference<>(recyclerView);
            float density = recyclerView.getResources().getDisplayMetrics().density;
            float touchSlop = ViewConfiguration.get(
                    recyclerView.getContext()).getScaledTouchSlop();
            float activationDistance = Math.max(
                    touchSlop, MIN_SWIPE_ACTIVATION_DP * density);
            minimumCommitDistance = MIN_SWIPE_COMMIT_DP * density;
            classifier = new GestureClassifier(
                    touchSlop, activationDistance, HORIZONTAL_DOMINANCE);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            if ("j".equals(name) && arguments != null && arguments.length == 2
                    && arguments[0] instanceof RecyclerView
                    && arguments[1] instanceof MotionEvent) {
                return onIntercept((RecyclerView) arguments[0], (MotionEvent) arguments[1]);
            }
            if ("l".equals(name) && arguments != null && arguments.length == 1
                    && arguments[0] instanceof MotionEvent) {
                onTouch((MotionEvent) arguments[0]);
                return null;
            }
            if ("d".equals(name) && arguments != null && arguments.length == 1
                    && arguments[0] instanceof Boolean) {
                if ((Boolean) arguments[0]) {
                    animateBack(active);
                    cancel();
                }
                return null;
            }
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(name)) {
                    return arguments != null && arguments.length == 1 && proxy == arguments[0];
                }
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("toString".equals(name)) return RecyclerTouchListener.class.getName();
            }
            return method.getReturnType() == Boolean.TYPE ? Boolean.FALSE : null;
        }

        private boolean onIntercept(RecyclerView recyclerView, MotionEvent event) {
            try {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    active = event.getPointerCount() == 1
                            ? findBindingAt(recyclerView, event.getX(), event.getY()) : null;
                    downX = event.getX();
                    classifier.onDown(event.getX(), event.getY());
                }
                if (!isCurrent(active)) {
                    animateBack(active);
                    cancel();
                    return false;
                }
                GestureClassifier.Result result = classifier.onEvent(event.getActionMasked(),
                        event.getPointerCount(), event.getX(), event.getY(), commitDistance());
                if (result == GestureClassifier.Result.CONSUME) {
                    updateDrag(active, event.getX() - downX);
                } else if (result == GestureClassifier.Result.CANCELLED) {
                    animateBack(active);
                    active = null;
                }
                return result == GestureClassifier.Result.CONSUME;
            } catch (Throwable ignored) {
                animateBack(active);
                cancel();
                return false;
            }
        }

        private void onTouch(MotionEvent event) {
            try {
                RecyclerView recyclerView = this.recyclerView.get();
                if (recyclerView == null) {
                    animateBack(active);
                    cancel();
                    return;
                }
                if (!isCurrent(active)) {
                    animateBack(active);
                    cancel();
                    return;
                }
                GestureClassifier.Result result = classifier.onEvent(event.getActionMasked(),
                        event.getPointerCount(), event.getX(), event.getY(), commitDistance());
                if (result == GestureClassifier.Result.CONSUME) {
                    updateDrag(active, event.getX() - downX);
                } else if (result == GestureClassifier.Result.COMPLETE) {
                    if (!completeSwipe(active, event)) animateBack(active);
                } else if (result == GestureClassifier.Result.CANCELLED) {
                    animateBack(active);
                }
                if (result == GestureClassifier.Result.COMPLETE
                        || result == GestureClassifier.Result.CANCELLED) cancel();
            } catch (Throwable ignored) {
                animateBack(active);
                cancel();
            }
        }

        private float commitDistance() {
            View item = active == null ? null : active.item();
            return swipeCommitDistance(
                    minimumCommitDistance, item == null ? 0 : item.getWidth());
        }

        boolean references(View item) {
            return active != null && active.item() == item;
        }

        void cancel() {
            active = null;
            classifier.reset();
        }
    }

    static float swipeCommitDistance(float minimumCommitDistance, int itemWidth) {
        return Math.max(minimumCommitDistance,
                Math.max(itemWidth, 1) * SWIPE_COMMIT_WIDTH_FRACTION);
    }

    static final class GestureClassifier {
        enum Result { PASS, CONSUME, COMPLETE, CANCELLED }
        private final float touchSlop;
        private final float activationDistance;
        private final float dominance;
        private boolean tracking;
        private boolean confirmed;
        private float downX;
        private float downY;

        GestureClassifier(float touchSlop, float activationDistance, float dominance) {
            this.touchSlop = touchSlop;
            this.activationDistance = activationDistance;
            this.dominance = dominance;
        }

        void onDown(float x, float y) {
            tracking = true;
            confirmed = false;
            downX = x;
            downY = y;
        }

        Result onEvent(
                int action, int pointerCount, float x, float y, float commitDistance) {
            if (!tracking) return Result.PASS;
            if (pointerCount != 1 || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                reset();
                return Result.CANCELLED;
            }
            float dx = x - downX;
            float leftDistance = -dx;
            float absDx = Math.abs(dx);
            float absDy = Math.abs(y - downY);
            if (!confirmed && action == MotionEvent.ACTION_MOVE) {
                if (dx >= touchSlop
                        || absDy >= touchSlop && absDx <= absDy * dominance) {
                    reset();
                    return Result.CANCELLED;
                }
                if (leftDistance >= activationDistance
                        && leftDistance > absDy * dominance) {
                    confirmed = true;
                    return Result.CONSUME;
                }
                return Result.PASS;
            }
            if (confirmed && action == MotionEvent.ACTION_MOVE) {
                if (leftDistance < touchSlop || leftDistance <= absDy * dominance) {
                    reset();
                    return Result.CANCELLED;
                }
                return Result.CONSUME;
            }
            if (action == MotionEvent.ACTION_UP) {
                boolean complete = confirmed && leftDistance >= commitDistance
                        && leftDistance > absDy * dominance;
                boolean wasConfirmed = confirmed;
                reset();
                return complete ? Result.COMPLETE
                        : wasConfirmed ? Result.CANCELLED : Result.PASS;
            }
            return confirmed ? Result.CONSUME : Result.PASS;
        }

        void reset() {
            tracking = false;
            confirmed = false;
        }
    }

    private static final class PublishRunnable implements Runnable {
        private final WeakReference<View> root;
        private final SubscriptionManagerSwipeVersion.Token version;

        PublishRunnable(
                WeakReference<View> root, SubscriptionManagerSwipeVersion.Token version) {
            this.root = root;
            this.version = version;
        }

        @Override
        public void run() {
            try {
                View value = root.get();
                if (value != null) publishPending(value, version);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class RemovalAttempt implements Runnable {
        private final Binding binding;
        private final RemovalPlan plan;
        volatile boolean persistenceReady;

        RemovalAttempt(Binding binding, RemovalPlan plan) {
            this.binding = binding;
            this.plan = plan;
        }

        @Override
        public void run() {
            boolean current;
            synchronized (LOCK) {
                View item = binding == null ? null : binding.item();
                current = persistenceReady && item != null && ITEMS.get(item) == binding
                        && VERSIONS.isCurrent(binding.version);
            }
            boolean originalRemovalSucceeded = current && executeSourceRemoval(plan);
            boolean refreshedRemovalSucceeded = false;
            if (current && !originalRemovalSucceeded) {
                RecyclerView recyclerView = binding.recyclerView();
                View item = binding.item();
                RemovalPlan refreshed = attestSourceRemoval(recyclerView, item);
                refreshedRemovalSucceeded = refreshed != null
                        && binding.videoId.equals(refreshed.videoId)
                        && executeSourceRemoval(refreshed);
            }
            boolean rebindRequired = shouldRequestPersistedHideRebind(
                    current, originalRemovalSucceeded, refreshedRemovalSucceeded);
            boolean rebindSucceeded = rebindRequired && requestPersistedHideRebind(binding);
            if (shouldRestorePersistedHidePresentation(rebindRequired, rebindSucceeded)) {
                animateBack(binding);
            }
            // Persistence is the user-visible result of a completed swipe. A delayed source plan
            // can become stale while the row animates or detaches; never reinterpret that race as
            // an undo. A current row is removed through a refreshed plan or rebound so the Litho
            // filter can apply the persisted decision without leaving a blank item.
        }
    }

    private static final class OwnershipDetachListener implements View.OnAttachStateChangeListener {
        private final WeakReference<View> view;

        OwnershipDetachListener(View view) {
            this.view = new WeakReference<>(view);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View detachedView) {
            View value = view.get();
            if (value == null) return;
            try {
                value.removeOnAttachStateChangeListener(this);
            } catch (Throwable ignored) {
            }
            synchronized (LOCK) {
                DETACH_LISTENERS.remove(value);
            }
            clearPreviousRootOwnership(value);
            detach(value);
        }
    }

    private static final class RemovalPlan {
        final WeakReference<RecyclerView> recyclerView;
        final WeakReference<View> item;
        final Object adapter;
        final Object owner;
        final Object adapterSource;
        final Object presenter;
        final Object source;
        final Object leaf;
        final Object sourceItem;
        final String videoId;
        final Object holder;
        final Method positionMethod;
        final Method adapterCountMethod;
        final Method sourceCountMethod;
        final Method sourceItemMethod;
        final Method leafCountMethod;
        final Method leafItemMethod;
        final Method removeMethod;
        final int globalPosition;
        final int localPosition;
        final int adapterCount;
        final int sourceCount;
        final int leafCount;

        RemovalPlan(RecyclerView recyclerView, View item, Object adapter, Object owner,
                Object adapterSource, Object presenter, Object source, Object leaf,
                Object sourceItem, String videoId, Object holder, Method positionMethod,
                Method adapterCountMethod, Method sourceCountMethod, Method sourceItemMethod,
                Method leafCountMethod, Method leafItemMethod, Method removeMethod,
                int globalPosition, int localPosition, int adapterCount, int sourceCount,
                int leafCount) {
            this.recyclerView = new WeakReference<>(recyclerView);
            this.item = new WeakReference<>(item);
            this.adapter = adapter;
            this.owner = owner;
            this.adapterSource = adapterSource;
            this.presenter = presenter;
            this.source = source;
            this.leaf = leaf;
            this.sourceItem = sourceItem;
            this.videoId = videoId;
            this.holder = holder;
            this.positionMethod = positionMethod;
            this.adapterCountMethod = adapterCountMethod;
            this.sourceCountMethod = sourceCountMethod;
            this.sourceItemMethod = sourceItemMethod;
            this.leafCountMethod = leafCountMethod;
            this.leafItemMethod = leafItemMethod;
            this.removeMethod = removeMethod;
            this.globalPosition = globalPosition;
            this.localPosition = localPosition;
            this.adapterCount = adapterCount;
            this.sourceCount = sourceCount;
            this.leafCount = leafCount;
        }
    }

    private static final class Binding {
        final WeakReference<View> root;
        final WeakReference<View> item;
        final WeakReference<RecyclerView> recyclerView;
        final String videoId;
        final String accountNamespace;
        final SubscriptionManagerSwipeVersion.Token version;
        Binding(View root, View item, RecyclerView recyclerView, String videoId,
                String accountNamespace, SubscriptionManagerSwipeVersion.Token version) {
            this.root = new WeakReference<>(root);
            this.item = new WeakReference<>(item);
            this.recyclerView = new WeakReference<>(recyclerView);
            this.videoId = videoId;
            this.accountNamespace = accountNamespace;
            this.version = version;
        }

        View root() {
            return root.get();
        }

        View item() {
            return item.get();
        }

        RecyclerView recyclerView() {
            return recyclerView.get();
        }
    }

    private static final class RootOwnership {
        final SubscriptionManagerSwipeVersion.Token version;
        WeakReference<View> item = new WeakReference<>(null);
        RootOwnership(SubscriptionManagerSwipeVersion.Token version) {
            this.version = version;
        }

        boolean matches(SubscriptionManagerSwipeVersion.Token expected) {
            return VERSIONS.matches(version, expected);
        }

        View item() {
            return item.get();
        }
    }

    private static final class Presentation {
        final int previousVisibility;
        final float previousAlpha;
        final float previousTranslationX;
        final boolean hadLayoutParams;
        final int previousHeight;
        final boolean hadMargins;
        final int previousTopMargin;
        final int previousBottomMargin;

        Presentation(View item) {
            previousVisibility = item.getVisibility();
            previousAlpha = item.getAlpha();
            previousTranslationX = item.getTranslationX();
            ViewGroup.LayoutParams layoutParams = item.getLayoutParams();
            hadLayoutParams = layoutParams != null;
            previousHeight = layoutParams == null ? 0 : layoutParams.height;
            hadMargins = layoutParams instanceof ViewGroup.MarginLayoutParams;
            if (hadMargins) {
                ViewGroup.MarginLayoutParams margins =
                        (ViewGroup.MarginLayoutParams) layoutParams;
                previousTopMargin = margins.topMargin;
                previousBottomMargin = margins.bottomMargin;
            } else {
                previousTopMargin = 0;
                previousBottomMargin = 0;
            }
        }
    }

    private static final class ItemRoute {
        final View item;
        final RecyclerView recyclerView;

        ItemRoute(View item, RecyclerView recyclerView) {
            this.item = item;
            this.recyclerView = recyclerView;
        }
    }
}
