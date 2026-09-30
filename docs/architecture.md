# 实现说明

## 三个部分

| 目录 | 内容 | 产物 |
| --- | --- | --- |
| `module/` | NinebotEnhance 仓库（子模块） | 模块的全部 Java 源码 |
| `extension/` | `ipc/Flavor.java`（替换模块里的同名文件）和 `embedded/` 运行时 | 与模块源码一起编译成一个 dex，`extensions/ninebotenhance.rve` |
| `patches/` | Kotlin 写的 ReVanced 补丁 | `ninebot-enhance-patches-<版本>.rvp` |

模块里与「谁来装钩子」有关的只有 `hook.HookHost` 这个接口：LSPosed 版由 `hook.XposedHost` 实现，这里由 `embedded.StaticHooks` 实现。`hook.NinebotHooks` 和各个观察类两边共用，一行不改。

`ipc.Flavor` 是两种构建唯一不同的源文件：模块包名（这里是 `cn.ninebot.ninebot`）、provider authority 前缀（`cn.ninebot.ninebot.enhance`，与 LSPosed 版不冲突，两者可以同时安装）、更新检查地址、许可证文本在安装包里的位置、录屏通知图标。

## 方法跳板

补丁对 `HookPolicy.interestingClass` 选中的类包装全部有方法体的方法（`<clinit>` 除外），另外包装两组靠对象而不是类名找到的目标：声明了 `onOutputFormatChanged` / `onOutputBufferAvailable` 的 MediaCodec 回调类，和 `NbFFmpegFrameRecorder` 父类链上的 `setVideoBitrate` / `setFrameRate`。

包装一个方法 `R m(A a, B b)`：

```
private synthetic R m$$ne(A a, B b) { 原方法体 }

R m(A a, B b) {
    Object slot = StaticHooks.hooks[id];
    if (slot == null) return m$$ne(a, b);
    return (R) StaticHooks.dispatch(slot, this, new Object[]{a, b});
}
```

- 孪生方法是私有的：分发器用反射调用它时不会落到子类的重写上。
- 跳板保留原方法的名字、访问标志和注解，调用方、JNI、反射看到的都和原来一样。
- `id` 是该方法在 `embedded.HookTable.TABLE` 里的行号；这个类由补丁生成，替换扩展里的空壳。运行时 `StaticHooks.hook(Method)` 把方法换算成同样的描述符（`Lpkg/Type;->name(params)ret`）查到槽位。
- 槽位数组固定 16384 个，补丁超过就报错；6.10.11 用了 1409 个。

构造函数的方法体不能搬到普通方法里，改成多带一个 `embedded.Twin` 参数的私有构造函数，原构造函数先委托给它，再在槽位非空时调 `StaticHooks.constructed`。所以构造函数的钩子只能在构造完成后观察，看得到参数和构造好的对象，改不了参数。只有 `HookPolicy.captureConstructors` 选中的类包装构造函数，用途是编码诊断。

`HookPolicy.twin` 让模块在按名字和签名挑选钩子目标时跳过孪生方法和孪生构造函数。

## 分发器

`StaticHooks.dispatch` 依次运行挂在这个槽位上的钩子，先注册的在最外层，最里面是孪生方法。钩子自己抛出的异常不会传给九号：记一次日志，然后当这个钩子不存在继续（还没调原方法就调，已经调过就返回原结果）；原方法抛出的异常原样传出。这与 LSPosed 版 `exceptionMode=protective` 的行为一致。主机测试 `tests/java/StaticHooksTest.java` 覆盖这些规则。

## 系统方法

模块在 LSPosed 下钩了几类系统方法，它们的代码不在安装包里，只能改调用点。`embedded.Redirects` 里每个带 `@Redirect` 注解的静态方法对应一个系统方法，补丁读注解得到目标和作用范围，把范围内的 `invoke-virtual` / `invoke-static` 改成对它的 `invoke-static`（寄存器不变，接收者成为第一个参数）：

| 目标 | 范围 | 6.10.11 的调用点 |
| --- | --- | --- |
| `LayoutInflater.inflate`（两种重载）、`View.inflate` | `cn.ninebot.*`、`androidx.databinding.*`、`androidx.asynclayoutinflater.*` | 619 |
| `View.draw(Canvas)` | `cn.ninebot.capture.*` | 2 |
| `MediaCodec` 的 configure、create、setCallback、getOutputFormat、dequeueOutputBuffer、start、setParameters、stop、reset、release | `cn.ninebot.*` | 22 |

系统内部自己发起的调用看不到。对模块够用：车辆页的卡片、采集视图的绘制、投屏编码器都是九号自己的代码在调。`Application.attach` 和 `ClassLoader.loadClass` 这两个钩子不需要：入口 provider 直接调 `NinebotHooks.attached`，补丁知道自己包装了哪些类，启动时逐个交给 `NinebotHooks.discovered`。

`inflate`、`draw`、`configure` 任何一个在安装包里找不到调用点，补丁就失败：那不是它认识的九号版本。

## 清单与文件

- 模块的 8 个 Activity、3 个 Service、4 个 provider 加进九号的清单，进程是 `:enhance`，Activity 的 `taskAffinity` 是 `dev.ichinomiya.ninebotenhance`（与九号的任务分开）。`ModuleActivity` 没有桌面入口，只保留 LSPosed 的 `MODULE_SETTINGS`。
- `embedded.EmbeddedEntry` 是主进程里的 provider，`initOrder` 最大。系统在 `Application.attachBaseContext` 之后、`Application.onCreate` 之前创建 provider，相当于 LSPosed 报告包已加载的时刻。
- 新增权限只有 `moe.shizuku.manager.permission.API_V23`，模块用到的其他权限九号清单里都有。
- `META-INF/xposed/`：入口还是 `hook.MirrorModule`，它在 `Flavor.EMBEDDED` 时不碰九号，只给导航 App 装观察钩子；导航状态经回环 TCP 送到 `:enhance` 进程。
- 许可证和声明放在 `assets/ninebotenhance/`。

清单修改走 ReVanced 的资源补丁（apktool 解码、aapt2 重建）。重建后资源 id 与原包逐一相同（32730 个），清单除新增内容外只有 `compileSdkVersion` 被 aapt2 改写。

补丁器重写 dex 后文件数比原包少（22 → 20），原包的 `classes21.dex`、`classes22.dex` 会留在包里，里面的类都被前面的文件遮住，不会被加载。

## 验证

- 主机：`scripts/build.py` 编译扩展、跑分发器测试、打补丁包。
- `scripts/Inspect.java` 打印补丁后的钩子表，把指定类反汇编成 smali。
- 设备：安装后看 logcat 的 `NinebotEnhance` 标签，应有 `MODULE … loaded embedded targets=…`、`HOOK CHECK Hook 目标 N/N 可用`、`BRIDGE connected`；`adb shell cmd package compile --reset cn.ninebot.ninebot` 后 `adb shell cmd package compile -m verify -f cn.ninebot.ninebot`，dex2oat 不应报补丁涉及的类校验失败（原包自带 4 条 `org.apache.commons.codec` 的校验错误，与补丁无关）。
