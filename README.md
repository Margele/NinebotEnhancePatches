# Ninebot Enhance Patches

QQ 群：1125342044

把 [Ninebot Enhance](https://github.com/Margele/NinebotEnhance) 直接打进九号出行安装包的 ReVanced 补丁。打完补丁的九号出行自带模块全部功能，九号本身不需要 LSPosed，也不需要另装模块 App。

功能与 LSPosed 版相同，见[模块的说明](https://github.com/Margele/NinebotEnhance#readme)和[使用手册](https://github.com/Margele/NinebotEnhance/blob/main/docs/manual.md)。

## 适用范围

| 项目 | 范围 |
| --- | --- |
| 九号出行 | 只支持 6.10.11（610114116）的未加固安装包；加固的版本补丁器改不了代码，本项目不做脱壳 |
| Android | 11–16；低于 Android 11 时九号照常运行，模块不启动 |
| 授权 | 虚拟显示器需 Root 或 Shizuku / Sui（Android 14+），授权对象是打过补丁的九号出行；投屏和绘制不需要 |
| 手机导航上仪表 | 需要导航 App 进程里的观察钩子：在 LSPosed（或同类框架）里把打过补丁的九号出行当模块启用，作用域勾高德地图、腾讯地图、百度地图 |

## 与 LSPosed 版的差别

- 重新签名：装之前要卸载官方版或其他签名的九号出行，本地数据会清空；之后每次更新都要用同一个密钥库重新打补丁。
- 跟签名绑定的第三方登录、推送、地图 key 可能失效，本项目不处理签名校验。
- 模块的服务和设置页运行在九号自己的 `:enhance` 进程，不需要自启动或关联启动权限。
- 桌面上没有单独的模块图标：权限检测页和首次引导从九号里的「设置 → 权限设置」进入。
- 通知使用权、附近的设备、电话状态等权限授给九号出行；通知使用权列表里模块的服务叫「Ninebot Enhance」。
- 构造函数上的观察钩子在构造完成后才运行，只用于编码诊断，不影响功能。

## 使用

需要 JDK 17+、Python 3.11+、Android SDK Platform 36.1 和 Build Tools 37.0.0，以及一份未修改的九号出行 6.10.11 安装包。

```sh
git submodule update --init
python scripts/build.py --sdk "$ANDROID_HOME" --jdk "$JAVA_HOME"
python scripts/patch.py --sdk "$ANDROID_HOME" --jdk "$JAVA_HOME" --apk 九号出行_6.10.11.apk
```

`build.py` 产出补丁包 `dist/ninebot-enhance-patches-<版本>.rvp`，`patch.py` 用 ReVanced CLI 把它打到安装包上，输出 `dist/<原文件名>-enhance.apk`。签名密钥库第一次运行时生成在 `signing/patched.keystore`，请保留：换了密钥就不能覆盖安装。

补丁包也可以直接交给 ReVanced CLI 6 使用：

```sh
java -jar revanced-cli-6.0.0-all.jar patch -p ninebot-enhance-patches-<版本>.rvp -b 九号出行_6.10.11.apk
```

补丁包里带有 ReVanced Manager 需要的 dex，但在手机上给三百多兆的安装包打补丁尚未测试，建议在电脑上用 CLI。

## 工作方式

- `module/` 是模块仓库的子模块。扩展（`extension/`）用模块的全部源码加上自己的 `ipc/Flavor.java` 和 `embedded/` 运行时编译成一个 dex，补丁把它并进九号。
- LSPosed 版在运行时挂钩子；这里在打补丁时完成：模块可能挂钩的每个方法，原方法体搬到私有的孪生方法 `名字$$ne`，原方法变成跳板，读一个槽位，空着就直接调孪生方法，被模块挂上后才转给分发器。挂哪些类由模块自己的 `hook.HookPolicy` 决定，补丁直接调用这个类。
- 系统方法（`LayoutInflater.inflate`、`View.draw`、`MediaCodec` 的几个方法）改不了，改的是九号里调用它们的地方，指向扩展的 `embedded.Redirects`。
- 清单里加入模块的服务、provider 和设置页（`:enhance` 进程），以及主进程里的入口 provider `embedded.EmbeddedEntry`，它在 `Application.onCreate` 之前启动模块。
- 安装包里加入 `META-INF/xposed/`，作用域只有三个导航 App。

更多细节见 [docs/architecture.md](docs/architecture.md)。

## 说明

- 补丁不改九号的任何业务逻辑：没被模块挂上的方法只多一次槽位读取。
- 只发布补丁，不发布打好补丁的安装包；请用自己取得的九号出行安装包。
- 补丁（`patches/`）以 GPL-3.0 发布；扩展和模块源码是 Apache-2.0。
- 本项目不是九号官方产品，也不是 ReVanced 官方项目。
- 如有侵权，请联系邮箱 shirona@ichinomiya.dev 处理。
