package dev.ichinomiya.ninebotenhance.ipc;

/**
 * The patched-APK build of the one file that differs from the LSPosed module: the module's components are declared in Ninebot's own
 * manifest, so "the module package" is Ninebot itself, and the hooks are written into Ninebot's code ahead of time.
 */
public final class Flavor {
    public static final boolean EMBEDDED = true;
    public static final String MODULE = "cn.ninebot.ninebot";
    /** Never the LSPosed module's authorities, so both can be installed side by side. */
    public static final String AUTHORITY = "cn.ninebot.ninebot.enhance";
    public static final String RELEASES_URL = "https://github.com/Margele/NinebotEnhancePatches/releases";
    public static final String LATEST_API = "https://api.github.com/repos/Margele/NinebotEnhancePatches/releases/latest";
    /** The licence texts and the notice are added under assets: Ninebot's APK has a META-INF of its own. */
    public static final String RESOURCES = "assets/ninebotenhance/";
    /** Ninebot's resource table is not the module's; a platform status icon stands in for the module's own. */
    public static int notificationIcon() { return android.R.drawable.stat_notify_sync_noanim; }
    private Flavor() {}
}
