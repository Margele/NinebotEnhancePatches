package dev.ichinomiya.ninebotenhance.embedded;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import dev.ichinomiya.ninebotenhance.hook.NinebotHooks;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.platform.ModuleResources;

/**
 * Where the module starts inside a patched Ninebot. The manifest declares this provider for the main process only, and Android
 * creates providers after the Application has its base context and before Application.onCreate: the moment LSPosed reports the
 * package as loaded. The provider itself answers nothing.
 */
public final class EmbeddedEntry extends ContentProvider {
    private static NinebotHooks hooks;
    @Override public boolean onCreate() {
        Context context = getContext();
        // The module needs Android 11; on anything older Ninebot runs as if it were not patched.
        if (context == null || Build.VERSION.SDK_INT < 30) return true;
        try { start(context); }
        catch (Throwable e) { Log.e(Protocol.TAG, "embedded module failed to start", e); }
        return true;
    }
    private static synchronized void start(Context context) {
        if (hooks != null) return;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ModuleResources.initialize(application.getApplicationInfo().sourceDir);
        ClassLoader loader = application.getClassLoader();
        NinebotHooks started = new NinebotHooks(StaticHooks.host(), Application.getProcessName());
        started.install(loader);
        started.attached(application, application);
        hooks = started;
        // Under LSPosed a class-load observer finds the targets outside the seed list; here the patch already knows every class it wrapped.
        Thread worker = new Thread(() -> {
            for (String name : StaticHooks.classes()) {
                try { started.discovered(Class.forName(name, false, loader)); }
                catch (Throwable ignored) {}
            }
        }, "Enhance-Discover");
        worker.setDaemon(true); worker.start();
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
