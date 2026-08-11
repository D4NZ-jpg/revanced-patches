package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import android.support.v7.widget.RecyclerView;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.youtube.shared.NavigationBar.NavigationButton;

/** Runtime-gated swipe ownership and gesture bridge for the verified YouTube 20.40.45 bind route. */
public final class SubscriptionManagerSwipeHandler {
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAX_MODEL_WRAPPERS = 8;
    private static final int MAX_CALLABLE_WRAPPERS = 4;
    private static final int MAX_PARENT_DEPTH = 16;
    private static final float MIN_SWIPE_DP = 48f;
    private static final float HORIZONTAL_DOMINANCE = 1.2f;

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
            String videoId = extractVideoIdFromVerifiedRoute(component);
            if (videoId == null || !recordPendingIdentity(root, version, videoId)) {
                discardPending(root, version);
                return;
            }

            // Parentage can settle after the bind call. Publishing always uses the same stale-safe path.
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

    private static boolean recordPendingIdentity(
            View root, SubscriptionManagerSwipeVersion.Token version, String videoId) {
        synchronized (LOCK) {
            RootOwnership ownership = ROOTS.get(root);
            if (ownership == null || !ownership.matches(version)) return false;
            ownership.videoId = videoId;
            return true;
        }
    }

    private static void publishPending(
            View root, SubscriptionManagerSwipeVersion.Token version) {
        String videoId;
        synchronized (LOCK) {
            RootOwnership ownership = ROOTS.get(root);
            if (ownership == null || !ownership.matches(version)
                    || ownership.videoId == null) return;
            videoId = ownership.videoId;
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
        String accountNamespace = SubscriptionManager.currentPersistentAccountNamespaceForSwipe();
        if (accountNamespace == null) {
            discardPending(root, version);
            return;
        }

        synchronized (LOCK) {
            RootOwnership ownership = ROOTS.get(root);
            if (ownership == null || !ownership.matches(version)
                    || !videoId.equals(ownership.videoId)) return;
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
            Binding binding = new Binding(root, route.item, route.recyclerView,
                    videoId, accountNamespace, version);
            ITEMS.put(route.item, binding);
            ownership.item = new WeakReference<>(route.item);
            ownership.videoId = null;
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

    private static void completeSwipe(final Binding binding) {
        final View item;
        synchronized (LOCK) {
            item = binding == null ? null : binding.item();
            View root = binding == null ? null : binding.root();
            RootOwnership ownership = root == null ? null : ROOTS.get(root);
            if (item == null || ITEMS.get(item) != binding
                    || !VERSIONS.isCurrent(binding.version)
                    || ownership == null || ownership.version != binding.version
                    || ownership.item() != item || !isSwipeContextEnabled()
                    || !SubscriptionManager.manuallyHideVideoForSwipe(
                            binding.videoId, binding.accountNamespace)) return;
        }
        try {
            item.post(new HideRunnable(new WeakReference<>(item), binding.version));
        } catch (Throwable ignored) {
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
            item.setVisibility(presentation.previousVisibility);
            item.requestLayout();
        } catch (Throwable ignored) {
        }
    }

    static String extractVideoIdFromVerifiedRoute(Object component) {
        try {
            // JADX synthesizes a defpackage source package; target DEX descriptors for gpq, glc,
            // twb, tvp, anso, and amtj are root classes and must remain unqualified here.
            Object model = readField(component, "e");
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            for (int depth = 0; isNamed(model, "gpq"); depth++) {
                if (depth >= MAX_MODEL_WRAPPERS
                        || seen.put(model, Boolean.TRUE) != null) return null;
                model = readFieldFromNamedClass(model, "gpq", "b");
                if (model == null) return null;
            }
            if (!isNamed(model, "glc")) return null;
            Object renderComponent = readFieldFromNamedClass(model, "glc", "a");
            if (!isNamed(renderComponent, "twb")) return null;
            Object callable = readFieldFromNamedClass(renderComponent, "twb", "b");
            seen.clear();
            for (int depth = 0; isNamed(callable, "tvp"); depth++) {
                if (depth >= MAX_CALLABLE_WRAPPERS
                        || seen.put(callable, Boolean.TRUE) != null) return null;
                callable = readFieldFromNamedClass(callable, "tvp", "a");
                if (callable == null) return null;
            }
            if (!isNamed(callable, "anso")) return null;
            Object payload = readFieldFromNamedClass(callable, "anso", "d");
            if (!isNamed(payload, "amtj")) return null;
            Object bytes = readFieldFromNamedClass(payload, "amtj", "c");
            return bytes instanceof byte[] ? extractEarliestFieldOneVideoId((byte[]) bytes) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String extractEarliestFieldOneVideoId(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES) return null;
        for (int offset = 0; offset + 13 <= bytes.length; offset++) {
            if ((bytes[offset] & 0xff) != 0x0a || (bytes[offset + 1] & 0xff) != 11) continue;
            for (int index = offset + 2; index < offset + 13; index++) {
                if (!isVideoIdCharacter(bytes[index] & 0xff)) return null;
            }
            return new String(bytes, offset + 2, 11, StandardCharsets.US_ASCII);
        }
        return null;
    }

    private static boolean isVideoIdCharacter(int character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '_' || character == '-';
    }

    private static Object readField(Object owner, String name) throws Exception {
        if (owner == null) return null;
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers())) return null;
                if (!field.isAccessible()) field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
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
        private Binding active;
        private Object proxy;

        RecyclerTouchListener(RecyclerView recyclerView) {
            this.recyclerView = new WeakReference<>(recyclerView);
            float density = recyclerView.getResources().getDisplayMetrics().density;
            float threshold = Math.max(ViewConfiguration.get(recyclerView.getContext()).getScaledTouchSlop(),
                    MIN_SWIPE_DP * density);
            classifier = new GestureClassifier(threshold, HORIZONTAL_DOMINANCE);
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
                if ((Boolean) arguments[0]) cancel();
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
                    classifier.onDown(event.getX(), event.getY());
                }
                if (!isCurrent(active)) {
                    cancel();
                    return false;
                }
                GestureClassifier.Result result = classifier.onEvent(event.getActionMasked(),
                        event.getPointerCount(), event.getX(), event.getY());
                if (result == GestureClassifier.Result.CANCELLED) active = null;
                return result == GestureClassifier.Result.CONSUME;
            } catch (Throwable ignored) {
                cancel();
                return false;
            }
        }

        private void onTouch(MotionEvent event) {
            try {
                RecyclerView recyclerView = this.recyclerView.get();
                if (recyclerView == null) {
                    cancel();
                    return;
                }
                if (!isCurrent(active)) {
                    cancel();
                    return;
                }
                GestureClassifier.Result result = classifier.onEvent(event.getActionMasked(),
                        event.getPointerCount(), event.getX(), event.getY());
                if (result == GestureClassifier.Result.COMPLETE) completeSwipe(active);
                if (result == GestureClassifier.Result.COMPLETE
                        || result == GestureClassifier.Result.CANCELLED) cancel();
            } catch (Throwable ignored) {
                cancel();
            }
        }

        boolean references(View item) {
            return active != null && active.item() == item;
        }

        void cancel() {
            active = null;
            classifier.reset();
        }
    }

    static final class GestureClassifier {
        enum Result { PASS, CONSUME, COMPLETE, CANCELLED }
        private final float threshold;
        private final float dominance;
        private boolean tracking;
        private boolean confirmed;
        private float downX;
        private float downY;

        GestureClassifier(float threshold, float dominance) {
            this.threshold = threshold;
            this.dominance = dominance;
        }

        void onDown(float x, float y) {
            tracking = true;
            confirmed = false;
            downX = x;
            downY = y;
        }

        Result onEvent(int action, int pointerCount, float x, float y) {
            if (!tracking) return Result.PASS;
            if (pointerCount != 1 || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                reset();
                return Result.CANCELLED;
            }
            float dx = x - downX;
            float dy = y - downY;
            float absDy = Math.abs(dy);
            if (!confirmed && action == MotionEvent.ACTION_MOVE) {
                if (dx >= threshold || absDy >= threshold && absDy >= Math.abs(dx)) {
                    reset();
                    return Result.CANCELLED;
                }
                if (-dx >= threshold && -dx > absDy * dominance) {
                    confirmed = true;
                    return Result.CONSUME;
                }
                return Result.PASS;
            }
            if (action == MotionEvent.ACTION_UP) {
                boolean complete = confirmed;
                reset();
                return complete ? Result.COMPLETE : Result.PASS;
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

    private static final class HideRunnable implements Runnable {
        private final WeakReference<View> item;
        private final SubscriptionManagerSwipeVersion.Token version;

        HideRunnable(
                WeakReference<View> item, SubscriptionManagerSwipeVersion.Token version) {
            this.item = item;
            this.version = version;
        }

        @Override
        public void run() {
            try {
                View value = item.get();
                if (value == null) return;
                synchronized (LOCK) {
                    Binding binding = ITEMS.get(value);
                    if (binding == null || binding.version != version
                            || !VERSIONS.isCurrent(version) || !value.isAttachedToWindow()) return;
                    if (!PRESENTATIONS.containsKey(value)) {
                        PRESENTATIONS.put(value, new Presentation(value.getVisibility()));
                    }
                    value.setVisibility(View.GONE);
                    value.requestLayout();
                }
            } catch (Throwable ignored) {
            }
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
        String videoId;
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

        Presentation(int previousVisibility) {
            this.previousVisibility = previousVisibility;
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
