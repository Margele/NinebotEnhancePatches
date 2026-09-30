# NinebotEnhancePatches 项目约定

把 NinebotEnhance 模块打进九号出行安装包的 ReVanced 补丁。模块仓库的约定（`module/CLAUDE.md`，即 D:\NinebotReverse\NinebotEnhance\CLAUDE.md）在这里同样有效：界面文案、只读蓝牙指令、不在后台运行、报错走 `ui.ErrorDialog` 等。

## 构建与验证
- 补丁包：`py -3 scripts/build.py --sdk <SDK> --jdk <JDK>`（本机没初始化子模块时加 `--module ../NinebotEnhance`）；它编译扩展、跑 `tests/java/StaticHooksTest`、用 Gradle 编 Kotlin 补丁、再用 d8 往 `.rvp` 里加 ReVanced Manager 用的 `classes.dex`。
- 打补丁：`py -3 scripts/patch.py --sdk <SDK> --jdk <JDK> --apk <九号 6.10.11 原包>`，原包在 `C:\Program Files\Netease\GameViewer\Download\九号出行_6.10.11.apk`。签名用 `signing/patched.keystore`（不进仓库），同一个密钥库才能覆盖安装。
- 看补丁结果：`java -cp tools/revanced-cli-6.0.0-all.jar scripts/Inspect.java <apk> <输出目录> [类名…]`。
- ReVanced Patcher 的 Maven 构件在 GitHub Packages 后面，要带 `read:packages` 的令牌；这里不依赖它，补丁直接对着 CLI 发布包 `tools/revanced-cli-6.0.0-all.jar` 编译（`build.py` 按固定 SHA-256 下载）。不要改回 `app.revanced.patches` Gradle 插件，除非 CI 和本机都有令牌。
- 补丁的日志器名字必须在 `app.revanced` 之下，CLI 和 Manager 只打印这个前缀。

## 与模块仓库的分工
- 功能代码只在模块仓库里改，这里不放模块功能。两种构建的差别只允许出现在 `ipc/Flavor.java`（这里的副本替换模块里的同名文件）和模块里少数 `Flavor.EMBEDDED` 分支。
- 要挂新的九号类：改模块的 `hook.HookPolicy`，补丁会自动包装；靠对象而不是类名找到的目标（MediaCodec 回调、录制器父类的 setter）在 `patches/.../StaticHooks.kt` 的 `selections()` 里。
- 要钩新的系统方法：在 `embedded.Redirects` 加一个带 `@Redirect` 的静态方法和对应槽位，补丁读注解自动改调用点。
- `StaticHooks.CAPACITY` 与补丁里的 `CAPACITY` 必须相等。
- 模块仓库的分支 `embedded-host` 带 `HookHost` / `Flavor` 这组改动，子模块指向它；合进模块 `main` 之前 LSPosed 版要在真机上过一遍。

## 边界
- 只支持未加固的九号安装包；不做脱壳、不绕过加固或签名校验、不处理反篡改检测。
- 只发布补丁包，不发布打好补丁的九号安装包。
- 补丁不改九号的业务逻辑：除了跳板、调用点重定向、清单和新增文件，不动任何东西。
- 提交与发版分开，与模块仓库相同：改完就提交，用户说「发版」才发。版本号跟模块的 `version.properties` 走。

## 已验证与未验证
- Android 14 模拟器（x86_64，带 ARM 转译）和 REDMI K100 Pro Max（Android 16）：启动、钩子 38/38、`:enhance` 服务连通、车辆页按钮行注入；模拟器上 dex2oat 全量校验无新增错误。
- 未验证：实车投屏三种画面方式、Root / Shizuku 起守护进程、导航 App 里以九号为模块的钩子、ReVanced Manager 在手机上打补丁。
- 模拟器环境在 `D:\NinebotReverse\tmp\emu`（假 SDK 根目录用目录联接指到真 SDK，系统镜像和 AVD 都在 D 盘）：`ANDROID_SDK_ROOT=D:/NinebotReverse/tmp/emu/sdk ANDROID_AVD_HOME=D:/NinebotReverse/tmp/emu/avd emulator -avd ne34 -no-window -no-audio -gpu host -feature -Vulkan`；`swiftshader_indirect` 在这台机器上起不来。
