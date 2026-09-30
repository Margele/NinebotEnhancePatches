package android.util;

/** Host stand-in: the platform stub jar throws from every method, and the dispatcher logs a failing hooker. */
public final class Log {
    public static final int ERROR = 6;
    public static final java.util.List<String> lines = new java.util.ArrayList<>();
    public static int e(String tag, String message, Throwable error) { lines.add(message); return 0; }
    public static int println(int priority, String tag, String message) { lines.add(message); return 0; }
    public static String getStackTraceString(Throwable error) { return String.valueOf(error); }
    private Log() {}
}
