# SceneHook

让 **Scene**（`com.omarea.vtools`）恒处于「已激活的完整 Pro」，并且**彻底不联网**，
变成一个纯本地工具。不用再改系统日期，也不用担心某天打开 App 被清掉授权。

- 目标版本：Scene **9.4.11**（versionCode `920260809`，targetSdk 30，单 dex 4525 类）
- 模块 API：libxposed **API 102**（LSPosed 2.2.x）
- 产物：`scenehook.apk`，约 25 KB，纯反射实现，不打包目标 App 的任何类
- 签名固定（仓库内含 `app/keystore.jks`），覆盖安装不会丢 LSPosed 的启用状态

---

## 一、逆向结论

分析方式：MT 管理器 MCP（`mt_apk_*` 的 dex 检索 / 伪代码 / 资源表）+ jadx 全量反编译对照。
所有结论都能在下面标出的类名里对上。

### 1.1 授权链路（全在 Java 层）

```
ActivityStartSplash$h.run()
  └─ new a.dp0(ctx).d()                    → ActivatedStateModel（启动分流用）
       ├─ type = a.t01.b()                  prefs["global"]["activate_v2_type"]
       └─ text = a.dp0.c(label)
            └─ a.cp0.toString()
                 └─ a.wm0#b()               进程内内存缓存（LinkedHashMap）
                      └─ a.cp0#c()          → a.q50.A(prefs["global"]["random_id2"])
                           └─ O0("activate", code, 2500)
                                └─ socket → scene-daemon → HTTP → "success@<到期毫秒>"
                                                                    / "expired" / "invalid" / "not-you"
```

关键点：

1. **本地没有任何「当前时间 vs 到期时间」的比较**。判决 100% 在服务端完成，
   而请求里带的是**客户端本地时间**——这才是「改系统日期能续试用期」的真正原因。
   所以单纯 hook `System.currentTimeMillis()` 一点用都没有。
2. `a.dp0#c(String)` 只做前缀解析：`success@<ts>` → 已激活；其余一律未激活。
3. 状态消费点只有 3 个：
   | 调用点 | 作用 |
   |---|---|
   | `ActivityStartSplash#h.run()` | 启动分流：已激活走正常流程，否则走提示页 |
   | `a.v12#Z1()` | 「关于 / 授权」页 |
   | `a.q02#b()` | 激活码兑换结果提示 |
4. **`ActivityMain` 每次打开都会复验一次**：

   ```java
   String code = t01.a();                       // prefs["global"]["random_id2"]
   if (code != null && code.length() > 0) {
       String r = q50.A(code);                  // ← 每次都问一次
       if (r.equals("expired") || r.equals("invalid") || r.equals("not-you")) {
           toast("授权已过期 [" + r + "]");
           t01.E0(null);                        // 清空 random_id2
           t01.F0(null);                        // 清空 activate_v2_type
       }
   }
   ```

   这就是「模块没生效时，打开一次就被清掉授权」的机制。也是必须 hook `q50#A` 而不是
   只改界面的原因。

### 1.2 网络面：只有 5 个入口，全部落到 `URL#openConnection`

| 入口 | 用途 |
|---|---|
| `a.h60` | HTTP 客户端基类（`b/m/k/i` 内部都是 `new URL(str).openConnection()`），6 个子类见下 |
| `a.aq0` | 基础地址 + 连通性探测（`omarea.com` / `scene7.omarea.com:8080` / `vtools.online`） |
| `a.zq0` | `/release-activate2`、`/account-bind`、`/account-unbind`、`/account-set-main` |
| `a.tp0` | `download.omarea.com/toolkit/*`（守护进程更新）、`/cloud-scheduler2`、`/cloud-scheduler-v2`、`/cloud-scheduler-threads` |
| `a.jp0` | `/scene-magisk-*`（Magisk 模块仓库） |
| `a.op0` | `/pvp/*`（跑分上报） |
| `a.iq0` | 云端配置同步 |
| `a.pk0` | 直接 `openConnection`：`magisk-modules-repo.github.io/submission/modules.json` |
| `a.kq0` | 直接 `openConnection`：`vtools.oss-cn-beijing.aliyuncs.com/vi/Scene9.json`（版本信息） |
| `a.rs1` | 直接 `openConnection`：`vi/lastversion.json`（更新检查） |
| `a.ep0` | 直接 `openConnection`：`addin/auto-skip-config-v1.json`（广告跳过配置） |

所以在 `java.net.URL#openConnection` 这一层设卡，一次覆盖全部。
只拦 `http/https`，`file:` / `jar:` 原样放行；WebView 走 native 栈，不在拦截范围
（用户主动点链接仍能打开网页，这是有意的）。

### 1.3 守护进程（scene-daemon）

- 就是 APK 里的 **`res/raw/daemon`**：2.28 MB、aarch64 静态 ELF、**整个正文熵 8.0、
  没有任何可读字符串**，是加密/自解密的二进制。首次启动时由 `a.ym0#b()` 释放到
  `files/scene-daemon`，用 `res/raw/up.sh` + busybox 以 root 起，监听 `127.0.0.1:14754`。
- **它不需要联网下载**（`a.tp0#z` 那个下载只是「升级守护进程」用的）。
  所以断网之后完整功能照旧可用，这一点已经用资源表核对过：`R.raw.daemon = 0x7f110003`。
- 协议是「4 字节长度 + 密文」，密钥来自 **`MD5(本 App 签名)`**：
  `a.q50#i0(ctx)` 把签名摘要写进 `q50.d`，`a.q50#H(String)` 用它加密每条 IPC 消息。
  **这就是唯一的防篡改机制**——重打包 / 重签名会让守护进程解不开消息、直接连不上。
  结论：**必须走运行时 hook，不能改包**。
- 它自己也会发 HTTP（`activate`、`download-profile` 两个 API），Java 层 hook 管不到
  另一个进程里的原生代码，所以在 **IPC 出口 `a.q50#O0`** 上拦。

### 1.4 检测面：没有

对全量 dex 和 `lib/arm64-v8a/libnative-lib.so` 做了关键词普查：

| 关键词 | 命中 |
|---|---|
| `Xposed` / `frida` / `LSPosed` / `de.robv` | 0（只有 Material 的 `exposed_dropdown_menu` 之类误报，以及 `R.string.menu_xposed_app` 这种**没有被任何代码引用的死资源**） |
| `TracerPid` / `/proc/self/maps` / `ptrace` | 0 |
| `isDebuggerConnected` / `isEmulator` / `ro.debuggable` | 0 |
| `isRoot` / `checkRoot` | 0（只有 `ActivityQuickStart$checkRoot` 这种**自己的**提权流程，不是检测） |
| 自签名校验 | 无（`a.c70` 是给**别的应用**算签名用的） |
| `libnative-lib.so` | 5.8 KB，无可读字符串，纯 JNI 桩 |

也就是说：**Scene 本身不做反调试 / 反 root / 反 hook**。断网之后连服务端都没有了，
「被检测」这条路径是彻底堵死的。

### 1.5 还有一个免费版开关

`AndroidManifest` 里 `<meta-data android:name="free_version" android:value="false"/>`，
`a.a30#c()` 读它（`true` = 专业包），`a.a30#b()` 是它的反。
免费包会在 `ActivityStartSplash#H()` 里把「Root 模式」入口 `setVisibility(8)`。
当前装的是 Pro 包，不受影响；模块顺手把它也按住了，防止哪天误装免费包。

---

## 二、Hook 设计

不碰网络栈之外的东西、不改包、不动目标 App 的数据目录，全部是运行时拦截。

### 2.1 授权（`LicenseHooks`，10 个）

| # | Hook 点 | 做法 | 目的 |
|---|---|---|---|
| 1 | `a.q50#A(String)` | 返回 `"success@" + <expiry 毫秒>` | **主判决**。短路守护进程与 HTTP，同时省掉启动时最多 6 秒的重试等待；覆盖 `ActivityMain` 的每次复验 |
| 2 | `a.q50#O0(String,String,Long)` | `activate` → success；`download-profile` → `"{}"` | 管住守护进程那一侧：不它让发 HTTP，也不再等 15 秒超时 |
| 3 | `a.dp0#d()` | `proceed()` 后改写结果 | 兜底：prefs 空/异常时也返回已激活 |
| 4 | `a.dp0#c(String)` | `proceed()` 后改写结果 | 兜底：被别处直接调用时同样生效 |
| 5 | `a.t01#b()` | 空 / `expired` / `free` / `unknown` → `trial` | 保证 `d()` 走试用分支，界面显示 `Pro(Trial)` |
| 6 | `ActivatedStateModel#getActivated()` | 恒 `true` | 最后一道保险，覆盖全部消费点 |
| 7 | `a.zq0#A()` | 返回 `null` | 跳过启动时那次 `/release-activate2`。原实现自己在网络失败时也 `return null`，语义一致 |
| 8 | `a.q50#h0()` | `basic` → 上次成功的 `working_mode` | 修 Scene 自己的启动竞态（见第五节） |
| 9 | `a.a30#b()` | `false` | 免费版标志位 |
| 10 | `a.a30#c()` | `true` | 免费版标志位 |

改写结果时会把 `text` / `typeName` / `permanent` 一起修好，文案从 App 自己的字符串资源里
按**名字**取（`getIdentifier("license_expiry"|"license_trial"|"license_perpetual")`），
不写死 resource id，换版本不会错位。实测文案就是 App 原本的 `Date of expiry:` + 日期，
和真授权完全一致。

### 2.2 断网（`NetworkGate`，2 个）

| # | Hook 点 | 做法 |
|---|---|---|
| 11 | `java.net.URL#openConnection()` | `http/https` → `throw new IOException("scenehook:offline <host>")` |
| 12 | `java.net.URL#openConnection(Proxy)` | 同上 |

抛 `IOException` 而不是返回空对象，是因为这本来就是「网络不可用」的既有语义：
`a.h60#b/m/p/h` 都声明了 `throws IOException`，其余 4 个直接调用的类全部包在
`try/catch` 里，`a.h60#k/i` 内部自己 catch 后返回 `null`。不会崩。

装完会**自检**：从模块自己的代码里 `new URL("http://127.0.0.1:1/...").openConnection()`，
必须抛出的异常信息里带 `scenehook:offline` 才算闸门生效。用 127.0.0.1:1 是为了万一
hook 没装上，也只是一次本地端口拒绝，不会真的产生外网流量。

### 2.3 设计原则

- 所有 `hook()` 各自 `try/catch`，失败只打 `WARN` 不抛——绝不能把目标 App 带崩
- 类名/方法名找不到就静默降级，不阻塞其余 hook
- 多进程 / 重复回调用静态标志防重入，避免 hook 串链
- `enabled=false` 时全部直接 `proceed()`，等于原版行为
- 装完立刻自检并打日志，不用等用户点进设置页

### 2.4 一个必须知道的坑：`ExceptionMode` 默认是 PROTECTIVE

第一版把断网闸门写成「在 hook 里 `throw new IOException(...)`」，装上去之后日志是：

```
W LSPosedFramework: Exception in hooker
W java.io.IOException: scenehook:offline 127.0.0.1
断网 hook: 2/2 生效, 闸门自检=FAIL
```

异常确实抛了，但**没到调用方**——libxposed 的 `HookBuilder` 默认 `ExceptionMode.PROTECTIVE`，
hook 里抛出的异常会被框架吃掉、记一条 WARN，然后回退到原方法（或给个默认值）。
结果就是「看着拦住了，其实没拦住」。

解法：给这两个 hook 显式加

```java
.setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
```

所以凡是「靠抛异常来实现」的 hook（断网、拒绝执行、越权），都必须显式 PASSTHROUGH；
反过来，纯返回值改写的 hook 保持默认 PROTECTIVE 更安全（自己写崩了不会连累目标 App）。
自检也改成了三态（`PASS` / `DEGRADED` / `FAIL`），能区分「抛了但被吞」和「真没拦住」。

---

## 三、配置

路径（按顺序探测，第一个可读的生效）：

1. `/sdcard/Download/scenehook/config.json`
2. `/storage/emulated/0/Download/scenehook/config.json`
3. `/sdcard/scenehook.json`
4. `/storage/emulated/0/scenehook.json`

```json
{
  "enabled": true,
  "mode": "trial",
  "expiry": "2099-12-31",
  "offline": true,
  "block_update": true,
  "assume_root": true,
  "force_pro": true,
  "trace_ipc": false,
  "debug": false
}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关掉后全部 hook 直接 `proceed()`，等于原版行为 |
| `mode` | `trial` | `trial` = 永远试用期（显示 `Pro(Trial)`）；`perpetual` = 永久授权（显示 `Pro(Permanent)`） |
| `expiry` | `2099-12-31` | 展示用的到期日，同时换算成时间戳喂给判决 |
| `offline` | `true` | **v2 新增**。在 `URL#openConnection` 上断掉全部 http/https |
| `block_update` | `true` | 掐断 `/release-activate2` 与 `download-profile`，保住本地 profile，启动不再等超时 |
| `assume_root` | `true` | 修复「首次进入显示基础功能、退出重进才恢复」的启动竞态 |
| `force_pro` | `true` | **v2 新增**。免费版标志位强制为专业版 |
| `trace_ipc` | `false` | **v2 新增**。打印每个 app↔daemon 的 API 名（去重），用来确认没有漏网的联网 API |
| `debug` | `false` | 打印每次 IPC 的 API 名与耗时 |

文件不存在时用内置默认值（即：已启用、试用期到 2099-12-31、断网），**开箱即用，不建文件也行**。
配置有 3 秒 TTL，改完不用重启 App。

> 想临时联网（比如首次装完想拉一次云端 profile）：把 `offline` 和 `block_update` 都置 `false`，
> 打开一次 Scene 让它在性能页拉完配置，再改回来。拉过一次之后本地就有
> `files/powercfg.sh` + `files/manifest.json`，之后永久断网也不影响。

---

## 四、构建与安装

### 4.1 GitHub Actions

推送到 `main` 即自动构建，产物同时发到 `latest` release：

```
https://github.com/m8458246/scenehook/releases/download/latest/scenehook.apk
```

打 tag（`v2.0.0`）则发对应版本 release。下载时若怀疑拿到旧包，加 `?ts=$(date +%s)` 破 CDN 缓存。

### 4.2 本地类型检查（不跑 Gradle）

```bash
curl -sSLO https://repo1.maven.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar
curl -sSLO https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar
unzip -o api-102.0.0.aar classes.jar

javac -nowarn -proc:none --release 17 -encoding UTF-8 \
  -cp "android-4.1.1.4.jar:classes.jar" \
  -d /tmp/tc $(find app/src/main/java -name '*.java')
```

### 4.3 安装

```bash
# 注意：先复制到 /data/local/tmp，/sdcard 上的包 system_server 读不了
cp scenehook.apk /data/local/tmp/
pm install -r /data/local/tmp/scenehook.apk
am force-stop com.omarea.vtools
```

然后在 **LSPosed 管理器**里启用模块并勾选 `com.omarea.vtools`（这一步必须手动点，
`lspctl` 没有启用命令，也不要直接改 `modules_config.db`）。

### 4.4 验证

```bash
logcat -s SceneHook:V
```

正常应看到：

（下面这段是 2.0.2 在 ColorOS / LSPosed 2.2.1 / 中文环境下实测的原始输出）

```
hook OK: a.q50#A(String)
hook OK: a.q50#O0(String,String,Long)
hook OK: a.dp0#d()
hook OK: a.dp0#c(String)
hook OK: a.t01#b()  [activate_v2_type]
hook OK: com.omarea.model.ActivatedStateModel#getActivated()
hook OK: a.zq0#A()
hook OK: a.q50#h0()
hook OK: a.a30#b()/#c()  [free_version]
SceneHook 安装完成: 授权类 10/10 个 hook 生效, 配置来源=default, enabled=true, mode=trial,
                    expiry=2099-12-31, offline=true, blockUpdate=true, assumeRoot=true
自检: type=trial, typeName=, activated=true, permanent=false, 到期=2099-12-31 (4102415999000), text=
hook OK: java.net.URL#openConnection()
hook OK: java.net.URL#openConnection(Proxy)
断网 hook: 2/2 生效, 闸门自检=PASS
自检(延迟): typeName=专业版(Trial), text=有效期至:2099-12-31, activated=true
```

- `自检` 那一行 `typeName=` / `text=` 是空的，是因为 `onPackageReady` 触发时
  `ActivityThread.currentApplication()` 还是 null，拿不到 Context 查不到字符串资源；
  紧接着的 `自检(延迟)` 会等 Application 出现后补一次，用来证明「按名字取资源」这条路通。
- 文案随系统语言变化（中文是 `专业版(Trial)` / `有效期至:`，英文是 `Pro(Trial)` / `Date of expiry:`），
  都是 App 自己的资源，不是模块写死的。
- 断网闸门自检 `PASS` 表示：模块自己发起的 `http://127.0.0.1:1/` 请求确实被自己的 hook
  以 `IOException: scenehook:offline` 拦下了。

再确认一下真的没有外网连接（只应看到 app↔daemon 的 127.0.0.1 回环）：

```bash
for f in /proc/net/tcp /proc/net/tcp6; do awk 'NR>1 && $8==10048' $f; done
# 10048 是 com.omarea.vtools 的 uid，输出的 local/remote 应全是 0100007F（127.0.0.1）
```

---

## 五、「首次进入是基础功能」的原因

这不是模块造成的，是 Scene 自己的启动竞态：

```java
public final String h0() { return Y() ? e : "basic"; }

public final boolean Y() {
    return X() != null || f2607a.c() || (f2606a == STATUS_RUNNING && !f2622e);
}
```

`f2606a` 是 daemon 状态，**每个新进程都从 `STATUS_STOP` 开始**，`e` 也从 `"basic"` 开始。
所以 App 刚起来时 `h0()` 恒为 `"basic"`，界面先按基础功能渲染；等异步的 root 探测
（`Z0()` → `e = "root"`）完成后才变完整功能。

更麻烦的是有些界面会在**构造时**就把 `isBasic` 缓存成字段（`a.vx1#t`），
所以只有「退出重进」才能让新 Activity 在 daemon 就绪后再构造一次。

`assume_root = true` 时，模块在 `h0()` 返回 `"basic"` 时改读 Scene 自己存的
`working_mode`（`SharedPreferences("global")` 的 `working_mode`，只有成功进过 root 才会是
`"root"`，写入点在 `ActivityStartSplash` 的授权弹窗确认回调里），用上次的成功模式顶替，
首次进入即完整功能。若担心 daemon 真没起来时界面与实际能力不符，可置为 `false`。

---

## 六、已知限制

1. **绑定版本**。`a.q50` / `a.dp0` / `a.t01` / `a.zq0` / `a.a30` 是 9.4.11 的混淆名。
   App 升级后混淆映射会变，需要按下面的路径重新定位（链路很短，几分钟能对上）：
   `ActivatedStateModel` → xref → `a.dp0` → `a.t01` / `a.q50`；网络面从 `a.h60` 的子类往下找。
2. **不是真正的离线破解**。模块只是让运行时状态恒为已激活，不伪造授权码、不伪造签名。
   服务端那边你的授权依然是过期状态；只是这个 App 再也问不到服务端了。
3. **守护进程是黑盒**。`res/raw/daemon` 正文全加密，无法静态确认它有没有「自己主动上报」的
   定时器。目前的证据是：它只响应 app 发来的 API，而 app 侧唯一的 HTTP 出口已经被
   `URL#openConnection` 掐死、唯一的 IPC 出口也被 `O0` 管住。真要验证，可以在断网状态下
   用 `ss -tnp` 看 `scene-daemon` 有没有非回环连接（`trace_ipc` 打出来的 API 名可作对照）。
4. **重签名会让守护进程连不上**（IPC 密钥派生自签名），所以不要改包，只挂模块。
5. 目标 App 若把授权状态缓存到 native 层（当前版本没有），Java 层 hook 会失效。

---

## 七、v1 → v2 变更

| 项 | v1 | v2 |
|---|---|---|
| 联网 | 只拦 `download-profile` + `/release-activate2`，其余照旧联网 | `URL#openConnection` 全量拦截 + IPC 出口拦截，双保险；装完自检 |
| `download-profile` 返回值 | `""`（上层 `new k30("")` 抛异常刷日志） | `"{}"`（合法 JSON，走正常空数据分支） |
| 授权判决覆盖 | 靠 `dp0` 改写 | 增加 `ActivityMain` 每次复验路径的说明与验证（`q50#A` 本就覆盖） |
| 免费版标志 | 未处理 | `a.a30#b()/#c()` |
| 排查手段 | `debug` | 新增 `trace_ipc`（API 名去重打印） |
| 自检 | 只验状态模型 | 状态模型 + 断网闸门双重自检 |
| 文档 | 授权链路 | 补全网络面全清单、守护进程机制、检测面普查结论 |

---

## 八、分析用的工具链

- 静态分析：MT 管理器 MCP（`mt_apk_*`）—— dex 类/方法检索、伪代码、资源表、xref
- 关键命令：
  - `mt_apk_search` target=`dex_strings` / `dex_names` —— 先按关键词定位「有没有」
  - `mt_apk_resource_read` —— 确认字符串资源语义（`license_expiry` = `"Date of expiry:"`）
  - `mt_apk_workspace_open` + jadx 全量反编译 —— 批量 grep 比逐类翻快得多
- 混淆名反推路径（本次实际走的）：

```
ActivatedStateModel（语义清晰，未混淆）
  └─ xref new-instance → a.dp0.c(String)        ← 6 个分支 = 状态机
       ├─ 读 a.t01.b() / a.cp0（wm0 内存缓存）
       └─ a.q50.A(String)                        ← 唯一的判决出口
网络面：
  grep "openConnection" → a.h60（基类）+ a.pk0 / a.kq0 / a.rs1 / a.ep0
  grep "extends h60"    → aq0 / zq0 / tp0 / jp0 / op0 / iq0
守护进程：
  grep "scene-daemon"   → a.q50（IPC 客户端）、a.tp0（升级下载）、a.ym0（解包）
  资源表 R.raw.daemon   → 内置 2.28MB ELF，不需要联网
```

**结论：混淆不可怕，从一个语义明确的数据模型出发，往上追生产者、往下追消费者，
两头一夹就出来了；网络面则反过来，从最底层的 `URL.openConnection` 往上找调用者，一遍就全了。**
