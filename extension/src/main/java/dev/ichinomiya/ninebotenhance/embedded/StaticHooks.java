package dev.ichinomiya.ninebotenhance.embedded;

import android.util.Log;
import dev.ichinomiya.ninebotenhance.hook.HookHost;
import dev.ichinomiya.ninebotenhance.hook.HookPolicy;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link HookHost} of a patched Ninebot APK. The patch moved the body of every method the module may hook into a private twin
 * and left a trampoline in its place: the trampoline reads its slot of {@link #hooks} and, while the slot is empty, calls the twin
 * directly. Hooking a method fills its slot; the trampoline then hands the call to {@link #dispatch}, which runs the hookers
 * around the twin. Platform methods cannot be wrapped, so their call sites were redirected to {@link Redirects} instead.
 */
public final class StaticHooks implements HookHost {
    /** The patch refuses to wrap more methods than this. */
    public static final int CAPACITY = 1 << 14;
    /** Read by every trampoline. A slot holds an {@link Entry} once something hooks the method. */
    public static final Object[] hooks = new Object[CAPACITY];
    private static final StaticHooks HOST = new StaticHooks();
    private static final Set<Object> reported = ConcurrentHashMap.newKeySet();
    private static volatile Map<String, Integer> table;
    public static StaticHooks host() { return HOST; }
    private StaticHooks() {}

    static final class Entry {
        final Executable target;
        /** The twin of a wrapped method, the platform method itself behind a redirect, or null for a constructor. */
        final Method original;
        volatile Hooker[] hookers = new Hooker[0];
        Entry(Executable target, Method original) { this.target = target; this.original = original; }
        Object original(Object self, Object[] args) throws Throwable {
            // A wrapped constructor has already run when its observers are called.
            if (original == null) return null;
            try { return original.invoke(self, args); }
            catch (InvocationTargetException e) { throw e.getCause() != null ? e.getCause() : e; }
        }
    }

    /** One hooker's view of a call; {@link #proceed} runs the hookers registered after it and finally the original. */
    private static final class Link implements Chain {
        final Entry entry; final Hooker[] hookers; final int index; final Object self; final Object[] args;
        boolean proceeded; Object result; Throwable thrown;
        Link(Entry entry, Hooker[] hookers, int index, Object self, Object[] args) {
            this.entry = entry; this.hookers = hookers; this.index = index; this.self = self; this.args = args;
        }
        @Override public Object getThisObject() { return self; }
        @Override public List<Object> getArgs() { return Collections.unmodifiableList(Arrays.asList(args)); }
        @Override public Object getArg(int index) { return args[index]; }
        @Override public Object proceed() throws Throwable { return proceed(args); }
        @Override public Object proceed(Object[] next) throws Throwable {
            proceeded = true; thrown = null;
            try { result = index + 1 < hookers.length ? run(entry, hookers, index + 1, self, next) : entry.original(self, next); return result; }
            catch (Throwable e) { thrown = e; throw e; }
        }
    }

    /** Called by the trampoline of a wrapped method and by {@link Redirects}; {@code slot} is what the trampoline read. */
    public static Object dispatch(Object slot, Object self, Object[] args) throws Throwable {
        Entry entry = (Entry) slot; Hooker[] hookers = entry.hookers;
        return hookers.length == 0 ? entry.original(self, args) : run(entry, hookers, 0, self, args);
    }
    /** Called by the trampoline of a wrapped constructor after the original body returned. */
    public static void constructed(Object slot, Object self, Object[] args) {
        try { dispatch(slot, self, args); }
        catch (Throwable e) { failed((Entry) slot, e); }
    }
    /**
     * A hooker that fails must not change what Ninebot sees: the original's own exception passes through, anything else is logged
     * once and the call continues as if the hooker were absent.
     */
    private static Object run(Entry entry, Hooker[] hookers, int index, Object self, Object[] args) throws Throwable {
        Link link = new Link(entry, hookers, index, self, args);
        try { return hookers[index].intercept(link); }
        catch (Throwable e) {
            if (link.proceeded && e == link.thrown) throw e;
            failed(entry, e);
            if (!link.proceeded) return link.proceed(args);
            if (link.thrown != null) throw link.thrown;
            return link.result;
        }
    }
    private static void failed(Entry entry, Throwable e) {
        if (reported.add(entry)) Log.e(Protocol.TAG, "hooker failed in " + entry.target, e);
    }
    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneak(Throwable e) throws T { throw (T) e; }

    @Override public Builder hook(Executable executable) {
        if (executable == null) throw new IllegalArgumentException("no executable");
        int redirect = Redirects.slot(executable);
        if (redirect >= 0) return hooker -> add(Redirects.slots, redirect, executable, (Method) executable, hooker);
        Integer id = table().get(key(executable));
        if (id == null) throw new IllegalStateException("not patched: " + key(executable));
        Method twin = executable instanceof Method ? twin((Method) executable) : null;
        return hooker -> add(hooks, id, executable, twin, hooker);
    }
    /**
     * The twin has the method's name plus the suffix, its parameters and its return type. The return type matters: a Kotlin lambda
     * declares invoke() twice, once returning Unit or void and once as the bridge returning Object, and the bridge's body calls the
     * other one. Taking the wrong twin would send the call back through the trampoline forever.
     */
    private static Method twin(Method method) {
        String name = method.getName() + HookPolicy.TWIN_SUFFIX; Class<?>[] parameters = method.getParameterTypes();
        for (Method candidate : method.getDeclaringClass().getDeclaredMethods()) {
            if (!candidate.getName().equals(name) || candidate.getReturnType() != method.getReturnType()
                    || !Arrays.equals(candidate.getParameterTypes(), parameters)) continue;
            candidate.setAccessible(true); return candidate;
        }
        throw new IllegalStateException("twin missing: " + key(method));
    }
    private static synchronized void add(Object[] slots, int index, Executable target, Method original, Hooker hooker) {
        Entry entry = (Entry) slots[index];
        if (entry == null) entry = new Entry(target, original);
        Hooker[] next = Arrays.copyOf(entry.hookers, entry.hookers.length + 1); next[next.length - 1] = hooker;
        entry.hookers = next; slots[index] = entry;
    }
    @Override public void log(int priority, String tag, String message, Throwable error) {
        Log.println(priority, tag, error == null ? message : message + '\n' + Log.getStackTraceString(error));
    }
    @Override public boolean framework() { return false; }
    @Override public String describe() { return "embedded targets=" + table().size(); }

    /** Every class the patch wrapped, in table order. */
    public static List<String> classes() {
        Set<String> names = new LinkedHashSet<>();
        for (String key : table().keySet()) names.add(key.substring(1, key.indexOf(";->")).replace('/', '.'));
        return new ArrayList<>(names);
    }
    private static Map<String, Integer> table() {
        Map<String, Integer> value = table;
        if (value != null) return value;
        synchronized (StaticHooks.class) {
            if (table != null) return table;
            // Insertion order is the slot order; classes() relies on it only for a stable log.
            Map<String, Integer> parsed = new java.util.LinkedHashMap<>();
            String text = HookTable.TABLE;
            if (text != null && !text.isEmpty()) { int id = 0; for (String line : text.split("\n")) parsed.put(line, id++); }
            table = parsed; return parsed;
        }
    }
    /** The dex method descriptor the patch wrote for this executable. */
    static String key(Executable executable) {
        StringBuilder text = new StringBuilder(type(executable.getDeclaringClass())).append("->")
                .append(executable instanceof Constructor ? "<init>" : executable.getName()).append('(');
        for (Class<?> parameter : executable.getParameterTypes()) text.append(type(parameter));
        return text.append(')').append(executable instanceof Method ? type(((Method) executable).getReturnType()) : "V").toString();
    }
    private static final Map<Class<?>, String> PRIMITIVES = new HashMap<>();
    static {
        PRIMITIVES.put(void.class, "V"); PRIMITIVES.put(boolean.class, "Z"); PRIMITIVES.put(byte.class, "B"); PRIMITIVES.put(char.class, "C");
        PRIMITIVES.put(short.class, "S"); PRIMITIVES.put(int.class, "I"); PRIMITIVES.put(long.class, "J"); PRIMITIVES.put(float.class, "F");
        PRIMITIVES.put(double.class, "D");
    }
    private static String type(Class<?> type) {
        if (type.isPrimitive()) return PRIMITIVES.get(type);
        // Class.getName() of an array is already a descriptor, with dots.
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
