import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") version "2.3.10" }

// The module sources: the git submodule by default, another checkout with -PmoduleDir=<path>.
val moduleDir = rootProject.file((findProperty("moduleDir") as String?) ?: "module")
val patchesVersion = (findProperty("patchesVersion") as String?) ?: "0.0.0-dev"

dependencies {
    // ReVanced Patcher 22 and smali, taken from the CLI release jar: the Maven artifacts sit behind GitHub Packages credentials.
    compileOnly(files(rootProject.file("tools/revanced-cli-6.0.0-all.jar")))
}

java { targetCompatibility = JavaVersion.VERSION_17; sourceCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); freeCompilerArgs.addAll("-Xcontext-parameters", "-Xexplicit-backing-fields", "-Xskip-prerelease-check") } }

sourceSets.main {
    // The patch asks the module's own policy class which Ninebot classes carry hook targets.
    java { srcDir(moduleDir.resolve("app/src/main/java")); include("dev/ichinomiya/ninebotenhance/hook/HookPolicy.java") }
    // The extension dex and the bundled licence texts, written by scripts/build.py.
    resources { srcDir(layout.buildDirectory.dir("generated/resources")) }
}

tasks.jar {
    archiveBaseName.set("ninebot-enhance-patches")
    archiveVersion.set("")
    archiveExtension.set("rvp")
    manifest.attributes(
        "Name" to "Ninebot Enhance Patches",
        "Description" to "Builds the Ninebot Enhance module into the Ninebot app",
        "Version" to patchesVersion,
        "Timestamp" to System.currentTimeMillis().toString(),
        "Source" to "git@github.com:Margele/NinebotEnhancePatches.git",
        "Author" to "Ninebot Enhance contributors",
        "Contact" to "https://github.com/Margele/NinebotEnhancePatches/issues",
        "Website" to "https://github.com/Margele/NinebotEnhancePatches",
        "License" to "GNU General Public License v3.0",
    )
}
