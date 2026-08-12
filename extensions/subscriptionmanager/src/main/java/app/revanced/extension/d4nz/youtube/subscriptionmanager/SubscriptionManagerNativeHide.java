package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact, synchronous bridge from a committed subscription swipe to YouTube's native Hide command.
 * Every target-owned object is structurally attested and ambiguity always falls back to local hide.
 */
public final class SubscriptionManagerNativeHide {
    private static final int MAX_VIEW_DEPTH = 12;
    private static final int MAX_VIEW_NODES = 128;
    private static final int MAX_MOUNT_ITEMS = 128;
    private static final int MAX_CALLBACKS = 16;
    private static final int MAX_MENU_ITEMS = 64;
    private static final int GESTURE_EVENT_CODE = -1336101728;
    private static final int OVERFLOW_HANDLER_ID = 98150882;
    private static final int HIDE_HANDLER_ID = 65153809;
    private static final int HIDE_ITEM_MASK = 2;
    private static final int HIDE_BAGK_MASK = 0x249;
    private static final String SENDER_VIEW_KEY =
            "com.google.android.libraries.youtube.rendering.elements.sender_view";
    private static final String INTERACTION_LOGGER_KEY =
            "com.google.android.libraries.youtube.logging.interaction_logger";

    private static final ThreadLocal<Attempt> ACTIVE_ATTEMPT = new ThreadLocal<>();

    private SubscriptionManagerNativeHide() {
    }

    static boolean isExactHideFingerprint(
            int itemMask, int bagkMask, boolean usesBagkE,
            int handlerId, String handlerClass) {
        return itemMask == HIDE_ITEM_MASK
                && bagkMask == HIDE_BAGK_MASK
                && (bagkMask & 0x40) != 0
                && usesBagkE
                && handlerId == HIDE_HANDLER_ID
                && "awvn".equals(handlerClass);
    }

    static boolean shouldDispatch(int matchCount, boolean senderOwned, boolean loggerPresent) {
        return matchCount == 1 && senderOwned && loggerPresent;
    }

    static boolean hasExactMenuCorrelation(
            boolean commandIdentity, boolean dispatcherIdentity, boolean senderIdentity) {
        return commandIdentity && dispatcherIdentity && senderIdentity;
    }

    /**
     * Injected at exact {@code jhv.b(avfw, Map)} entry. It records the precise transformed command
     * only while the selected card's {@code sov.a} invocation is synchronously on the stack.
     */
    @SuppressWarnings("unused")
    public static void onMenuCommandResolved(Object handler, Object command, Map<?, ?> map) {
        Attempt attempt = ACTIVE_ATTEMPT.get();
        if (attempt == null || attempt.menuCommand != null) return;
        try {
            if (!isExactNamed(handler, "jhv") || !isExactNamed(command, "avfw") || map == null
                    || Looper.myLooper() != Looper.getMainLooper()) return;
            View overflow = attempt.overflow.get();
            if (overflow == null || map.get(SENDER_VIEW_KEY) != overflow
                    || commandHandlerId(command) != OVERFLOW_HANDLER_ID
                    || !"bafw".equals(commandHandlerClass(command))) return;
            Object dispatcher = readExactField(handler, "jhv", "c", "ahex");
            if (dispatcher == null) return;
            attempt.menuCommand = command;
            attempt.dispatcher = dispatcher;
        } catch (Throwable ignored) {
        }
    }

    /** Invoked synchronously from the committed RecyclerView swipe gesture. */
    public static boolean tryHide(View card, MotionEvent event) {
        if (card == null || event == null || ACTIVE_ATTEMPT.get() != null
                || Looper.myLooper() != Looper.getMainLooper()
                || !card.isAttachedToWindow()) return false;
        try {
            OverflowTarget target = findOverflowTarget(card);
            if (target == null || !target.view.isAttachedToWindow()
                    || !isDescendant(card, target.view)) return false;
            Attempt attempt = new Attempt(card, target.view, target.callback, target.innerCommand);
            ACTIVE_ATTEMPT.set(attempt);
            try {
                Object touch = target.touchConstructor.newInstance(
                        Math.max(target.view.getWidth(), 1) / 2f,
                        Math.max(target.view.getHeight(), 1) / 2f);
                target.invokeMethod.invoke(target.callback, target.view, touch, event);
                return attempt.dispatched;
            } finally {
                ACTIVE_ATTEMPT.remove();
            }
        } catch (Throwable ignored) {
            ACTIVE_ATTEMPT.remove();
            return false;
        }
    }

    /**
     * Injected at the entry of exact {@code anqc.a(anqb)}. Returning true suppresses presentation.
     * A swipe-owned invocation is always consumed, even when native dispatch fails, so a failed
     * native attempt cannot unexpectedly open the overflow menu.
     */
    @SuppressWarnings("unused")
    public static boolean onMenuResolved(Object coordinator, Object arguments) {
        Attempt attempt = ACTIVE_ATTEMPT.get();
        if (attempt == null) return false;
        if (attempt.resolved) return true;
        attempt.resolved = true;
        try {
            if (!isExactNamed(coordinator, "anqc") || !isExactNamed(arguments, "anqb")
                    || Looper.myLooper() != Looper.getMainLooper()) return true;
            View card = attempt.card.get();
            View overflow = attempt.overflow.get();
            if (card == null || overflow == null || !card.isAttachedToWindow()
                    || !overflow.isAttachedToWindow() || !isDescendant(card, overflow)) return true;

            Object menu = readExactField(arguments, "anqb", "b", "bagi");
            Object endpointMap = readExactField(arguments, "anqb", "c", "arbo");
            Object menuCommand = readExactField(arguments, "anqb", "f", "avfw");
            Object dispatcher = readExactField(coordinator, "anqc", "b", "ahex");
            if (!(endpointMap instanceof Map<?, ?>) || menu == null || menuCommand == null
                    || dispatcher == null) return true;

            Map<?, ?> originalMap = (Map<?, ?>) endpointMap;
            boolean senderOwned = originalMap.get(SENDER_VIEW_KEY) == overflow;
            if (!hasExactMenuCorrelation(
                    menuCommand == attempt.menuCommand,
                    dispatcher == attempt.dispatcher,
                    senderOwned)) return true;
            boolean loggerPresent = originalMap.get(INTERACTION_LOGGER_KEY) != null;
            HideMatch match = findHideMatch(menu);
            if (!shouldDispatch(match.count, senderOwned, loggerPresent)
                    || match.command == null) return true;

            HashMap<Object, Object> copiedMap = new HashMap<>();
            copiedMap.putAll(originalMap);
            ClassLoader loader = dispatcher.getClass().getClassLoader();
            Class<?> avfw = exactClass(loader, "avfw");
            Method dispatch = exactInstanceMethod(
                    exactClass(loader, "aewu"), "c", Void.TYPE, avfw, Map.class);
            dispatch.invoke(dispatcher, match.command, copiedMap);
            attempt.dispatched = true;
        } catch (Throwable ignored) {
            // The active swipe remains locally authoritative; never guess a native action.
        }
        return true;
    }

    private static OverflowTarget findOverflowTarget(View card) throws Exception {
        IdentityHashMap<Object, Boolean> callbacks = new IdentityHashMap<>();
        OverflowTarget[] match = new OverflowTarget[1];
        int[] nodes = new int[1];
        scanView(card, card, 0, nodes, callbacks, match);
        return callbacks.size() == 1 ? match[0] : null;
    }

    private static void scanView(
            View card, View view, int depth, int[] nodes,
            IdentityHashMap<Object, Boolean> callbacks, OverflowTarget[] match) throws Exception {
        if (view == null || depth > MAX_VIEW_DEPTH || nodes[0]++ >= MAX_VIEW_NODES
                || callbacks.size() > 1) return;
        if (isExactNamed(view, "com.facebook.litho.ComponentHost")) {
            scanComponentHost(card, view, callbacks, match);
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        int count = Math.min(group.getChildCount(), MAX_VIEW_NODES);
        for (int index = 0; index < count; index++) {
            scanView(card, group.getChildAt(index), depth + 1, nodes, callbacks, match);
            if (callbacks.size() > 1) return;
        }
    }

    private static void scanComponentHost(
            View card, View host, IdentityHashMap<Object, Boolean> callbacks,
            OverflowTarget[] match) throws Exception {
        ClassLoader loader = host.getClass().getClassLoader();
        Class<?> hostClass = exactClass(loader, "com.facebook.litho.ComponentHost");
        Method countMethod = exactInstanceMethod(hostClass, "a", Integer.TYPE);
        Method itemMethod = exactInstanceMethod(hostClass, "b", exactClass(loader, "gqg"),
                Integer.TYPE);
        int count = (Integer) countMethod.invoke(host);
        if (count < 0 || count > MAX_MOUNT_ITEMS) return;
        Class<?> gqg = exactClass(loader, "gqg");
        Class<?> gdh = exactClass(loader, "gdh");
        Method metadataMethod = exactStaticMethod(gdh, "a", gdh, gqg);
        for (int index = 0; index < count; index++) {
            Object mountItem = itemMethod.invoke(host, index);
            if (!isExactClass(mountItem, gqg)) continue;
            Object content = readExactField(mountItem, "gqg", "a", null);
            if (content == null || !isExactNamed(content, "android.widget.ImageView")
                    || !(content instanceof View) || !isDescendant(card, (View) content)) continue;
            Object metadata = metadataMethod.invoke(null, mountItem);
            Object nodeInfo = readExactField(metadata, "gdh", "a", "gev");
            Object handler = readExactField(nodeInfo, "gev", "w", "gcq");
            if (handler == null || readExactIntField(handler, "gcq", "c") != GESTURE_EVENT_CODE) {
                continue;
            }
            Object owner = readExactField(handler, "gcq", "b", "sky");
            Object callbackValue = readExactField(owner, "sky", "E", null);
            if (!(callbackValue instanceof List<?>)) continue;
            List<?> callbackList = (List<?>) callbackValue;
            if (callbackList.size() > MAX_CALLBACKS) return;
            for (Object callback : callbackList) {
                if (!isExactNamed(callback, "sov")) continue;
                Object resolver = readExactField(callback, "sov", "f", "rks");
                Object innerCommand = extractInnerCommand(resolver);
                if (innerCommand == null || commandHandlerId(innerCommand) != OVERFLOW_HANDLER_ID
                        || !"bafw".equals(commandHandlerClass(innerCommand))) continue;
                callbacks.put(callback, Boolean.TRUE);
                if (callbacks.size() != 1) {
                    match[0] = null;
                    return;
                }
                Class<?> tuy = exactClass(loader, "tuy");
                Constructor<?> touchConstructor = tuy.getDeclaredConstructor(
                        Float.TYPE, Float.TYPE);
                if (!Modifier.isPublic(touchConstructor.getModifiers())) return;
                Method invoke = exactInstanceMethod(callback.getClass(), "a", Void.TYPE,
                        View.class, tuy, MotionEvent.class);
                match[0] = new OverflowTarget(
                        (View) content, callback, innerCommand, touchConstructor, invoke);
            }
        }
    }

    private static Object extractInnerCommand(Object resolver) throws Exception {
        if (!isExactNamed(resolver, "rks")) return null;
        ClassLoader loader = resolver.getClass().getClassLoader();
        Method resolve = exactInstanceMethod(
                resolver.getClass(), "j",
                exactClass(loader, "com.google.protos.youtube.elements.CommandOuterClass$Command"));
        Object outer = resolve.invoke(resolver);
        if (outer == null || !"ateh".equals(outer.getClass().getSuperclass().getName())) return null;
        Class<?> atdw = exactClass(loader, "atdw");
        Object extension = readExactStaticField(loader, "axtl", "a", "atek");
        if (extension == null || !atdw.isInstance(extension)) return null;
        Method getExtension = exactInstanceMethod(
                outer.getClass().getSuperclass(), "b", Object.class, atdw);
        Object inner = getExtension.invoke(outer, extension);
        return isExactNamed(inner, "avfw") ? inner : null;
    }

    private static HideMatch findHideMatch(Object menu) throws Exception {
        Object items = readExactField(menu, "bagi", "c", null);
        if (!(items instanceof Collection<?>)) return HideMatch.NONE;
        Collection<?> collection = (Collection<?>) items;
        if (collection.size() > MAX_MENU_ITEMS) return HideMatch.AMBIGUOUS;
        int count = 0;
        Object matchedCommand = null;
        ClassLoader loader = menu.getClass().getClassLoader();
        Class<?> bagf = exactClass(loader, "bagf");
        Class<?> aiml = exactClass(loader, "aiml");
        Method commandMethod = exactStaticMethod(
                aiml, "fq", exactClass(loader, "avfw"), bagf);
        for (Object item : collection) {
            if (!isExactClass(item, bagf)) continue;
            int itemMask = readExactIntField(item, "bagf", "b");
            Object bagk = (itemMask & HIDE_ITEM_MASK) == 0
                    ? null : readExactField(item, "bagf", "d", "bagk");
            int bagkMask = bagk == null ? 0 : readExactIntField(bagk, "bagk", "b");
            Object command = commandMethod.invoke(null, item);
            Object bagkCommand = bagk != null && (bagkMask & 0x40) != 0
                    ? readExactField(bagk, "bagk", "e", "avfw") : null;
            boolean usesBagkE = command != null && command == bagkCommand;
            int handlerId = command == null ? -1 : commandHandlerId(command);
            String handlerClass = command == null ? null : commandHandlerClass(command);
            if (!isExactHideFingerprint(
                    itemMask, bagkMask, usesBagkE, handlerId, handlerClass)) continue;
            count++;
            matchedCommand = command;
            if (count > 1) return HideMatch.AMBIGUOUS;
        }
        return count == 1 ? new HideMatch(1, matchedCommand) : HideMatch.NONE;
    }

    private static int commandHandlerId(Object command) throws Exception {
        if (!isExactNamed(command, "avfw")) return -1;
        ClassLoader loader = command.getClass().getClassLoader();
        Method method = exactStaticMethod(
                exactClass(loader, "asmq"), "J", Integer.TYPE,
                exactClass(loader, "ateh"));
        return (Integer) method.invoke(null, command);
    }

    private static String commandHandlerClass(Object command) throws Exception {
        if (!isExactNamed(command, "avfw")) return null;
        ClassLoader loader = command.getClass().getClassLoader();
        Method method = exactStaticMethod(
                exactClass(loader, "aeww"), "c", Object.class, command.getClass());
        Object handler = method.invoke(null, command);
        return handler == null ? null : handler.getClass().getName();
    }

    private static Object readExactField(
            Object owner, String ownerClass, String fieldName, String valueClass) throws Exception {
        if (!isExactNamed(owner, ownerClass)) return null;
        Field field = owner.getClass().getDeclaredField(fieldName);
        if (Modifier.isStatic(field.getModifiers())) return null;
        if (!field.isAccessible()) field.setAccessible(true);
        Object value = field.get(owner);
        return valueClass == null || isExactNamed(value, valueClass) ? value : null;
    }

    private static int readExactIntField(
            Object owner, String ownerClass, String fieldName) throws Exception {
        if (!isExactNamed(owner, ownerClass)) return Integer.MIN_VALUE;
        Field field = owner.getClass().getDeclaredField(fieldName);
        if (Modifier.isStatic(field.getModifiers()) || field.getType() != Integer.TYPE) {
            return Integer.MIN_VALUE;
        }
        if (!field.isAccessible()) field.setAccessible(true);
        return field.getInt(owner);
    }

    private static Object readExactStaticField(
            ClassLoader loader, String ownerClass, String fieldName, String valueClass)
            throws Exception {
        Class<?> owner = exactClass(loader, ownerClass);
        Field field = owner.getDeclaredField(fieldName);
        if (!Modifier.isStatic(field.getModifiers())) return null;
        if (!field.isAccessible()) field.setAccessible(true);
        Object value = field.get(null);
        return isExactNamed(value, valueClass) ? value : null;
    }

    private static Method exactInstanceMethod(
            Class<?> owner, String methodName, Class<?> returnType, Class<?>... parameters)
            throws Exception {
        Method method = owner.getDeclaredMethod(methodName, parameters);
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != returnType) {
            throw new NoSuchMethodException(methodName);
        }
        if (!method.isAccessible()) method.setAccessible(true);
        return method;
    }

    private static Method exactStaticMethod(
            Class<?> owner, String methodName, Class<?> returnType, Class<?>... parameters)
            throws Exception {
        Method method = owner.getDeclaredMethod(methodName, parameters);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != returnType) {
            throw new NoSuchMethodException(methodName);
        }
        if (!method.isAccessible()) method.setAccessible(true);
        return method;
    }

    private static Class<?> exactClass(ClassLoader loader, String name) throws Exception {
        Class<?> type = Class.forName(name, false, loader);
        if (!name.equals(type.getName())) throw new ClassNotFoundException(name);
        return type;
    }

    private static boolean isExactNamed(Object value, String className) {
        return value != null && className.equals(value.getClass().getName());
    }

    private static boolean isExactClass(Object value, Class<?> type) {
        return value != null && value.getClass() == type;
    }

    private static boolean isDescendant(View card, View child) {
        if (card == null || child == null) return false;
        View current = child;
        for (int depth = 0; depth <= MAX_VIEW_DEPTH && current != null; depth++) {
            if (current == card) return true;
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static final class OverflowTarget {
        final View view;
        final Object callback;
        final Object innerCommand;
        final Constructor<?> touchConstructor;
        final Method invokeMethod;

        OverflowTarget(View view, Object callback, Object innerCommand,
                Constructor<?> touchConstructor, Method invokeMethod) {
            this.view = view;
            this.callback = callback;
            this.innerCommand = innerCommand;
            this.touchConstructor = touchConstructor;
            this.invokeMethod = invokeMethod;
        }
    }

    private static final class Attempt {
        final WeakReference<View> card;
        final WeakReference<View> overflow;
        final Object callback;
        final Object innerCommand;
        Object menuCommand;
        Object dispatcher;
        boolean resolved;
        boolean dispatched;

        Attempt(View card, View overflow, Object callback, Object innerCommand) {
            this.card = new WeakReference<>(card);
            this.overflow = new WeakReference<>(overflow);
            this.callback = callback;
            this.innerCommand = innerCommand;
        }
    }

    private static final class HideMatch {
        static final HideMatch NONE = new HideMatch(0, null);
        static final HideMatch AMBIGUOUS = new HideMatch(2, null);
        final int count;
        final Object command;

        HideMatch(int count, Object command) {
            this.count = count;
            this.command = command;
        }
    }
}
