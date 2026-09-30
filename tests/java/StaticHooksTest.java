import dev.ichinomiya.ninebotenhance.embedded.HookTable;
import dev.ichinomiya.ninebotenhance.embedded.Redirects;
import dev.ichinomiya.ninebotenhance.embedded.StaticHooks;
import dev.ichinomiya.ninebotenhance.embedded.Twin;
import dev.ichinomiya.ninebotenhance.hook.HookHost;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The dispatcher against methods written the way the patch rewrites them: a trampoline that reads its slot and a private twin.
 * Whether the generated dex is what ART accepts is checked on a device, not here.
 */
public final class StaticHooksTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneak(Throwable e) throws T { throw (T) e; }

    /** What a wrapped class looks like after patching; the slot numbers are the lines of {@link #TABLE}. */
    public static class Sample {
        public final List<String> seen = new ArrayList<>();
        public int add(int a, long b) {
            Object slot = StaticHooks.hooks[0];
            if (slot == null) return add$$ne(a, b);
            try { return (Integer) StaticHooks.dispatch(slot, this, new Object[]{a, b}); } catch (Throwable e) { throw sneak(e); }
        }
        private int add$$ne(int a, long b) { seen.add("add"); return a + (int) b; }
        public static String join(String[] parts) {
            Object slot = StaticHooks.hooks[1];
            if (slot == null) return join$$ne(parts);
            try { return (String) StaticHooks.dispatch(slot, null, new Object[]{parts}); } catch (Throwable e) { throw sneak(e); }
        }
        private static String join$$ne(String[] parts) { return String.join("+", parts); }
        public void fail(String message) {
            Object slot = StaticHooks.hooks[2];
            if (slot == null) { fail$$ne(message); return; }
            try { StaticHooks.dispatch(slot, this, new Object[]{message}); } catch (Throwable e) { throw sneak(e); }
        }
        private void fail$$ne(String message) { seen.add("fail"); throw new IllegalStateException(message); }
        public void untouched() {}
        public Sample(String name) {
            this(name, (Twin) null);
            Object slot = StaticHooks.hooks[3];
            if (slot != null) StaticHooks.constructed(slot, this, new Object[]{name});
        }
        private Sample(String name, Twin marker) { seen.add("built " + name); }
    }
    /** A subclass override must never stand in for the twin of the method it overrides. */
    public static final class Child extends Sample {
        public Child() { super("child"); }
        @Override public int add(int a, long b) { return super.add(a, b) * 100; }
    }

    /** javac adds the bridges: this class declares get() and get$$ne() twice each, returning String and returning Object. */
    public interface Source<T> { T get(); T get$$ne(); }
    public static final class Text implements Source<String> {
        @Override public String get() { return get$$ne(); }
        @Override public String get$$ne() { return "text"; }
    }

    private static final String OWNER = "LStaticHooksTest$Sample;";
    private static final String TABLE = OWNER + "->add(IJ)I\n" + OWNER + "->join([Ljava/lang/String;)Ljava/lang/String;\n"
            + OWNER + "->fail(Ljava/lang/String;)V\n" + OWNER + "-><init>(Ljava/lang/String;)V";

    public static void main(String[] args) throws Throwable {
        HookTable.TABLE = TABLE;
        StaticHooks host = StaticHooks.host();
        Method add = Sample.class.getDeclaredMethod("add", int.class, long.class);
        Method join = Sample.class.getDeclaredMethod("join", String[].class);
        Method fail = Sample.class.getDeclaredMethod("fail", String.class);
        List<String> order = new ArrayList<>();

        Sample sample = new Sample("first");
        check(sample.add(2, 3) == 5 && Sample.join(new String[]{"a", "b"}).equals("a+b"), "unhooked calls run the original");
        check(sample.seen.equals(List.of("built first", "add")), "the original body ran once");
        check(StaticHooks.classes().equals(List.of("StaticHooksTest$Sample")) && host.describe().equals("embedded targets=4") && !host.framework(),
                "the table names the wrapped class");

        host.hook(add).intercept(chain -> {
            order.add("outer before " + chain.getArgs());
            Object result = chain.proceed(new Object[]{(Integer) chain.getArg(0) + 10, chain.getArg(1)});
            order.add("outer after " + result);
            return (Integer) result + 1;
        });
        host.hook(add).intercept(chain -> { order.add("inner " + chain.getThisObject().getClass().getSimpleName()); return chain.proceed(); });
        check(sample.add(2, 3) == 16, "arguments and result pass through both hookers");
        check(order.equals(List.of("outer before [2, 3]", "inner Sample", "outer after 15")), "the first hooker registered is the outermost: " + order);
        check(new Child().add(1, 1) == 1300, "the twin of the overridden method runs, not the override again");

        host.hook(join).intercept(chain -> chain.getThisObject() == null ? "static:" + chain.proceed() : "?");
        check(Sample.join(new String[]{"x"}).equals("static:x"), "a static method has no receiver");

        // A hooker that fails is taken out of the call, before or after the original ran.
        android.util.Log.lines.clear();
        host.hook(join).intercept(chain -> { throw new UnsupportedOperationException("before"); });
        check(Sample.join(new String[]{"y"}).equals("static:y"), "a hooker failing before proceed does not stop the call");
        host.hook(join).intercept(chain -> { chain.proceed(); throw new UnsupportedOperationException("after"); });
        check(Sample.join(new String[]{"z"}).equals("static:z"), "a hooker failing after proceed keeps the original result");
        check(android.util.Log.lines.size() == 1, "a failing method is reported once: " + android.util.Log.lines);

        // The original's own exception is Ninebot's to see, unchanged.
        host.hook(fail).intercept(chain -> { try { return chain.proceed(); } finally { order.add("observed"); } });
        Sample failing = new Sample("second");
        try { failing.fail("boom"); check(false, "the original exception must propagate"); }
        catch (IllegalStateException e) { check(e.getMessage().equals("boom") && failing.seen.equals(List.of("built second", "fail")), "same exception, original ran once"); }
        check(order.contains("observed") && android.util.Log.lines.size() == 1, "the original's exception is not a hooker failure");

        // Constructors are observed after the body ran; proceed has nothing left to run.
        List<Object> built = new ArrayList<>();
        host.hook(Sample.class.getDeclaredConstructor(String.class)).intercept(chain -> {
            built.add(chain.getArg(0)); built.add(((Sample) chain.getThisObject()).seen.toString()); return chain.proceed();
        });
        new Sample("third");
        check(built.equals(List.of("third", "[built third]")), "the constructor observer sees the argument and the finished object: " + built);

        try { host.hook(Sample.class.getDeclaredMethod("untouched")); check(false, "an unwrapped method cannot be hooked"); }
        catch (IllegalStateException e) { check(e.getMessage().equals("not patched: " + OWNER + "->untouched()V"), e.getMessage()); }

        // Two methods that differ only in their return type each get their own twin.
        Method twin = StaticHooks.class.getDeclaredMethod("twin", Method.class); twin.setAccessible(true);
        for (Method method : Text.class.getDeclaredMethods()) if (method.getName().equals("get"))
            check(((Method) twin.invoke(null, method)).getReturnType() == method.getReturnType(), "the twin of " + method + " returns the same type");

        // Platform methods go through Redirects: hooking one fills its slot and the stub stops calling the platform directly.
        Method inflate = android.view.LayoutInflater.class.getDeclaredMethod("inflate", int.class, android.view.ViewGroup.class, boolean.class);
        List<Object> inflated = new ArrayList<>();
        host.hook(inflate).intercept(chain -> { inflated.addAll(chain.getArgs()); return null; });
        check(Redirects.inflate((android.view.LayoutInflater) null, 7, null) == null && inflated.equals(java.util.Arrays.asList(7, null, false)),
                "the two-argument inflate reaches the three-argument hook: " + inflated);
        Method encoder = android.media.MediaCodec.class.getDeclaredMethod("createEncoderByType", String.class);
        host.hook(encoder).intercept(chain -> { inflated.add(chain.getArg(0)); return null; });
        check(Redirects.createEncoderByType("video/avc") == null && inflated.contains("video/avc"), "a static platform method is redirected");
        System.out.println("PASS: " + assertions + " assertions (static hook dispatcher)");
    }
}
