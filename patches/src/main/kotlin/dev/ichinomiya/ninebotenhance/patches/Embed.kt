package dev.ichinomiya.ninebotenhance.patches

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.logging.Logger

private const val NINEBOT = "cn.ninebot.ninebot"
private const val MODULE = "dev.ichinomiya.ninebotenhance"

/** Flavor.AUTHORITY of the extension. */
private const val AUTHORITY = "$NINEBOT.enhance"

/** The module's service, providers and screens run here, as they do in the module's own process under LSPosed. */
private const val PROCESS = ":enhance"
private const val THEME = "@android:style/Theme.Material.Light.NoActionBar"

// ReVanced CLI and Manager only print loggers under this name.
private val logger = Logger.getLogger("app.revanced.patches.ninebotenhance")

/**
 * The code half: adds the module's classes, points Ninebot's calls of the hooked platform methods at the extension and wraps the
 * methods the module hooks. Nothing else in Ninebot's code changes, and nothing runs differently until the module fills a slot.
 */
private val embedCodePatch = bytecodePatch {
    extendWith("extensions/ninebotenhance.rve")
    apply {
        val redirected = redirectPlatformCalls()
        // A Ninebot build without these call sites is not one the module's entry points were written for.
        for (required in listOf("inflate", "draw", "configure")) if ((redirected[required] ?: 0) == 0)
            throw PatchException("no $required call site found; this Ninebot build is not supported")
        val wrapped = wrapHookTargets()
        if (wrapped == 0) throw PatchException("no hook target found; this Ninebot build is not supported")
        logger.info("Redirected platform calls: " + redirected.entries.joinToString { "${it.key}=${it.value}" })
        logger.info("Wrapped $wrapped methods and constructors")
    }
}

private fun Document.element(tag: String, vararg attributes: Pair<String, String>): Element =
    createElement(tag).apply { attributes.forEach { (name, value) -> setAttribute("android:$name", value) } }

private fun Element.filter(action: String, vararg categories: String): Element {
    val filter = ownerDocument.element("intent-filter")
    filter.appendChild(ownerDocument.element("action", "name" to action))
    categories.forEach { filter.appendChild(ownerDocument.element("category", "name" to it)) }
    appendChild(filter)
    return this
}

/** The licence texts and the notice the module's About page opens, copied under assets (Flavor.RESOURCES). */
private val BUNDLED = listOf(
    "META-INF/NOTICE.txt", "META-INF/licenses/Apache-2.0.txt", "META-INF/licenses/NinebotEnhance-Apache-2.0.txt", "META-INF/licenses/Shizuku-MIT.txt",
)

/**
 * LSPosed metadata: the patched Ninebot is itself a module whose scope is the navigation apps, where the module's observe-only
 * probes read the turn-by-turn state. Ninebot is not in the scope; its hooks are already in its code.
 */
private val XPOSED = mapOf(
    "META-INF/xposed/java_init.list" to "$MODULE.hook.MirrorModule\n",
    "META-INF/xposed/module.prop" to "minApiVersion=101\ntargetApiVersion=101\nstaticScope=true\nexceptionMode=protective\n",
    "META-INF/xposed/scope.list" to "com.autonavi.minimap\ncom.tencent.map\ncom.baidu.BaiduMap\n",
)

@Suppress("unused")
val embedNinebotEnhancePatch = resourcePatch(
    name = "Embed Ninebot Enhance",
    description = "Builds the Ninebot Enhance module into the app: virtual display, screen capture and drawn pictures for the dashboard cast, " +
        "with the module's widgets, lamp and BMS links. No LSPosed is needed for Ninebot itself.",
) {
    compatibleWith(NINEBOT("6.10.11"))
    dependsOn(embedCodePatch)

    apply {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val application = document.getElementsByTagName("application").item(0) as Element
            if (manifest.getAttribute("package") != NINEBOT) throw PatchException("not the Ninebot app")

            // Every other permission the module's manifest asks for is already in Ninebot's.
            val shizuku = "moe.shizuku.manager.permission.API_V23"
            val permissions = document.getElementsByTagName("uses-permission")
            if ((0 until permissions.length).none { (permissions.item(it) as Element).getAttribute("android:name") == shizuku })
                manifest.insertBefore(document.element("uses-permission", "name" to shizuku), application)

            fun component(tag: String, name: String, vararg attributes: Pair<String, String>) =
                document.element(tag, "name" to "$MODULE.$name", "process" to PROCESS, *attributes).also { application.appendChild(it) }
            // Their own task, as when the module was an app of its own: opening one never drags Ninebot's task forward.
            fun screen(name: String, vararg attributes: Pair<String, String>) = component(
                "activity", name, "exported" to "false", "excludeFromRecents" to "true", "taskAffinity" to MODULE, "label" to "Ninebot Enhance",
                "theme" to THEME, *attributes,
            )

            // No launcher entry of its own: the permission page opens from the settings inside Ninebot, and from LSPosed's module list.
            screen("ui.ModuleActivity", "exported" to "true").filter("android.intent.action.MAIN", "de.robv.android.xposed.category.MODULE_SETTINGS")
            for (name in listOf("SetupGuideActivity", "LaunchAppPickerActivity", "NotificationSettingsActivity", "LampSettingsActivity",
                "BmsSettingsActivity", "TouchSettingsActivity")) screen("ui.$name")
            screen("ui.ScreenCaptureConsentActivity", "theme" to "@android:style/Theme.Translucent.NoTitleBar", "resizeableActivity" to "true")

            component("service", "notification.MirrorNotificationListener", "exported" to "true", "label" to "Ninebot Enhance",
                "permission" to "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")
                .filter("android.service.notification.NotificationListenerService")
            component("service", "service.ScreenCaptureService", "exported" to "false", "foregroundServiceType" to "mediaProjection")
            component("service", "service.FrameBridgeService", "exported" to "true")

            component("provider", "service.LogShareProvider", "authorities" to "$AUTHORITY.logs", "exported" to "false", "grantUriPermissions" to "true")
            component("provider", "service.RootBridgeProvider", "authorities" to "$AUTHORITY.root", "exported" to "true")
            component("provider", "service.NaviContentProvider", "authorities" to "$AUTHORITY.navi", "exported" to "true")
            // Shizuku delivers its Binder to "<package>.shizuku" of the process that uses it.
            application.appendChild(document.element("provider", "name" to "rikka.shizuku.ShizukuProvider", "process" to PROCESS,
                "authorities" to "$NINEBOT.shizuku", "exported" to "true", "multiprocess" to "false",
                "permission" to "android.permission.INTERACT_ACROSS_USERS_FULL"))
            // Main process, created before Application.onCreate and before any other provider: where the hooks are armed.
            application.appendChild(document.element("provider", "name" to "$MODULE.embedded.EmbeddedEntry", "authorities" to "$AUTHORITY.entry",
                "exported" to "false", "initOrder" to Int.MAX_VALUE.toString()))
        }

        val loader = object {}.javaClass.classLoader
        for (path in BUNDLED) {
            val target = get("assets/ninebotenhance/$path", false)
            target.parentFile.mkdirs()
            (loader.getResourceAsStream("ninebotenhance/$path") ?: throw PatchException("bundled file missing: $path")).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        for ((path, text) in XPOSED) get(path, false).apply { parentFile.mkdirs(); writeText(text) }
    }
}
