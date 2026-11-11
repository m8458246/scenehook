# SceneHook

让 **Scene**（`com.omarea.vtools`）恒处于「已激活的完整试用状态」的 LSPosed 模块，
不用再改系统日期。

- 目标版本：Scene **9.4.11**（versionCode `920260809`）
- 模块 API：libxposed **API 102**（LSPosed 2.2.x）
- 产物：`scenehook.apk`，约 20 KB，纯反射实现，不打包目标 App 的任何类

---

## 一、逆向结论：授权是怎么判的

### 1.1 入口链路

```
ActivityStartSplash$h.run()
  └─ new a.dp0(appContext).d()            ← 授权状态机出口，返回 ActivatedStateModel
       ├─ type     = a.t01.b()            ← SharedPreferences["activate_v2_type"]
       └─ text     = a.dp0.c(label)       ← 真正判断"过没过期"的地方
             └─ a.cp0.c()
                  └─ a.q50.A(prefs["random_id2"])
                        └─ 拿激活码请求服务端，返回 "success@<到期毫秒>" / "expired" / "invalid" / "not-you"
```

`a.q50.A(String)` 的实现（反编译）：

```java
public final String A(String code) {
    byte[] b = code.getBytes(Charset.defaultCharset());
    long start = System.currentTimeMillis();
    while (true) {
        String r = O0("activate", new String(b, xe2.a), 2500L);   // 打服务端
        if (!"error".equals(r)) return r;
        Thread.sleep(300);
        if (System.currentTimeMillis() - start >= 6000) return "error";
    }
}
```

### 1.2 为什么"改系统日期"能生效

服务端拿不到你的真实时间——请求参数里带的是**客户端本地时间**：

| 接口 | 时间参数 |
|---|---|
| `a.q50.A` → `activate` | 客户端当前时间参与构造 |
| `a.zq0.A` → `/release-activate2` | `request_time = new Date().getTime()` |

服务端用这个时间跟授权到期时间比，得出 `pass` / `expired`。
所以把日期拨到 2026-08-15（试用期内），服务端就回 `success@...`，App 自然满血。
这正是实测现象，不是错觉。

### 1.3 状态是怎么落地的

`a.dp0.c(String)` 只看返回串的前缀：

```java
if (value.startsWith("success@")) {
    long ts = Long.parseLong(value.substring(value.indexOf('@') + 1));
    if (ts < 1_000_000_000_000L) ts *= 1000;      // 秒 → 毫秒
    model.setActivated(true);
    model.setText(label + new SimpleDateFormat("yyyy-MM-dd").format(new Date(ts)));
} else if (value.equals("expired")) {
    model.setActivated(false);                     // 顺手清掉 prefs
} else if (value.equals("empty")) {
    model.setActivated(false);
} else {
    model.setActivated(false);                     // invalid / not-you / 其他
}
```

**注意：本地没有任何"当前时间 vs 到期时间"的比较**。整个判定 100% 在服务端完成。
所以单纯 hook `System.currentTimeMillis()` 是**没用**的——时间根本没参与本地比较。

### 1.4 全 App 只有 3 个地方消费授权状态

`ActivatedStateModel.getActivated()` 的调用点（dispatch xref 全量）：

| 调用点 | 作用 |
|---|---|
| `ActivityStartSplash$h.run()` | 启动分流：已激活走正常流程，否则走提示流程 |
| `v12.Z1()` | 「关于/授权」页面显示 |
| `q02.b(String,String)` | 激活码提交后的结果提示 |

字符串 `"trial"` 在整个 dex 里只出现在 `a.dp0.d()`；`"expired"` 只出现在 `a.dp0` 和
`ActivityMain$a`（激活码兑换流程）。也就是说**没有第二套隐藏的功能门禁**，
把 `activated` 按住为 true 就够。

---

## 二、Hook 设计

不碰网络、不改包、不动目标 App 的数据目录，全部是运行时拦截。

| # | Hook 点 | 做法 | 目的 |
|---|---|---|---|
| 1 | `a.q50.A(String)` | 直接返回 `"success@" + <远期时间戳>` | **主判决**。短路服务端，同时省掉启动时最多 6 秒的重试等待 |
| 2 | `a.dp0.d()` | `proceed()` 后改写结果 | 兜底：prefs 里是空/异常时也返回已激活 |
| 3 | `a.dp0.c(String)` | `proceed()` 后改写结果 | 兜底：被别处直接调用时同样生效 |
| 4 | `a.t01.b()` | 空 / `expired` / `free` / `unknown` → `"trial"` | 保证 `d()` 走试用分支，界面显示 `Pro(Trial)` |
| 5 | `ActivatedStateModel.getActivated()` | 恒返回 `true` | 最后一道保险，覆盖全部 3 个消费点 |

改写结果时会把 `text` / `typeName` 一起修好，文案从 App 自己的字符串资源里按**名字**取
（`getIdentifier("license_expiry", ...)`），不写死 resource id，换版本不会错位。

设计原则（沿用过往项目踩坑经验）：

- 所有 `hook()` 各自 `try/catch`，失败只打 `WARN` 不抛——绝不能把目标 App 带崩
- 类名找不到就静默降级，不阻塞其余 hook
- 多进程 / 重复回调用静态标志防重入，避免 hook 串链
- 装完立刻自检并打日志，不用等用户点进设置页

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
  "block_update": true,
  "assume_root": true,
  "debug": false
}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关掉后全部 hook 直接 `proceed()`，等于原版行为 |
| `mode` | `trial` | `trial` = 永远试用期（显示 Pro(Trial)）；`perpetual` = 永久授权（显示 Pro(Permanent)） |
| `expiry` | `2099-12-31` | 展示用的到期日，同时换算成时间戳喂给判决 |
| `block_update` | `true` | 掐断 `/release-activate2` 与 `download-profile`，保住本地 profile，启动不再等 15 秒超时 |
| `assume_root` | `true` | 修复"首次进入显示基础功能、退出重进才恢复 root"的启动竞态 |
| `debug` | `false` | 打开后打印每次 daemon IPC 的 API 名与耗时，用来定位"慢在哪" |

文件不存在时用内置默认值（即已启用、试用期到 2099-12-31），**开箱即用，不建文件也行**。
配置有 3 秒 TTL，改完不用重启 App。

---

## 四、构建与安装

### 4.1 GitHub Actions

推送到 `main` 即自动构建，产物同时发到 `latest` release：

```
https://github.com/m8458246/scenehook/releases/download/latest/scenehook.apk
```

打 tag（`v1.0.0`）则发对应版本 release。

> 下载 Release 资产时若怀疑拿到旧包，加个 `?ts=$(date +%s)` 破 CDN 缓存。

### 4.2 本地类型检查（不跑 Gradle）

```bash
javac -nowarn -proc:none --release 17 -encoding UTF-8 \
  -cp "android-stub.jar:libxposed-api.jar" \
  -d /tmp/tc $(find app/src/main/java -name '*.java')
```

`android-stub.jar` 可用 `com.google.android:android:4.1.1.4`，
`libxposed-api.jar` 是 `io.github.libxposed:api:102.0.0` 这个 AAR 里的 `classes.jar`。

### 4.3 安装

```bash
# 注意：先复制到 /data/local/tmp，/sdcard 上的包 system_server 读不了
cp scenehook.apk /data/local/tmp/
pm install -r /data/local/tmp/scenehook.apk
am force-stop com.omarea.vtools
```

然后在 **LSPosed 管理器**里启用模块并勾选 `com.omarea.vtools`（这一步需要手动点，
`lspctl` 没有启用命令，也不要直接改 `modules_config.db`）。

签名固定（仓库内含 `app/keystore.jks`），后续覆盖安装不会丢 LSPosed 的启用状态。

### 4.4 验证

```bash
logcat -s SceneHook:V
```

正常应看到：

```
模块加载: process=com.omarea.vtools framework=LSPosed ... api=102
目标包就绪: com.omarea.vtools
hook OK: a.q50#A(String)
hook OK: a.dp0#d()
hook OK: a.dp0#c(String)
hook OK: a.t01#b()  [activate_v2_type]
hook OK: com.omarea.model.ActivatedStateModel#getActivated()
SceneHook 安装完成: 5/5 个 hook 生效, 配置来源=default, enabled=true, mode=trial, expiry=2099-12-31
自检: type=trial, activated=true, 到期=2099-12-31 (4102444799000), text=...
```

打开 Scene → 「关于」页应显示 `Pro(Trial)`，到期日 `2099-12-31`。

---

## 五、"首次进入是基础功能"的原因

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

更麻烦的是有些界面会在**构造时**就把 `isBasic` 缓存成字段（如 `vx1.t`），
所以只有"退出重进"才能让新 Activity 在 daemon 就绪后再构造一次。

`assume_root = true` 时，模块在 `h0()` 返回 `"basic"` 时改读 Scene 自己存的
`working_mode`（只有成功进过 root 才会是 `"root"`），用上次的成功模式顶替，
首次进入即完整功能。若担心 daemon 真没起来时界面与实际能力不符，可置为 `false`。

---

## 六、已知限制

1. **绑定版本**。`a.q50` / `a.dp0` / `a.t01` 是 9.4.11 的混淆名。App 升级后混淆映射会变，
   需要按「01/05 文档」的方法重新定位：从 `ActivatedStateModel` 的 xref 往上找
   `dp0.c` → `cp0.c` → `q50.A`，链路很短，几分钟就能重新对上。
2. **不是真正的离线破解**。模块只是让运行时状态恒为已激活，不伪造授权码、不伪造签名。
   服务端那边你的授权依然是过期状态；如果 App 以后加了"必须在服务端激活过才给功能"的硬门禁，
   这套方案需要跟着调整。
3. **纯本地生效**。不拦截网络，不改任何 prefs，不改 APK。关掉模块即恢复原版行为。
4. 目标 App 若把授权状态缓存到 native 层（当前版本没有，`lib/` 下只有加密和 sqlite 相关的 so），
   Java 层 hook 会失效，需要另行处理。

---

## 六、分析用的工具链

- 静态分析：MT 管理器 MCP（`mt_apk_*`）—— dex 类/方法检索、Smali、伪代码、资源表、xref
- 关键命令：
  - `mt_apk_search` target=`dex_names` / `dex_strings` —— 先按关键词定位「有没有」
  - `mt_apk_dex_xref` —— 从数据模型反推生产者和消费者，比按名字猜快得多
  - `mt_apk_resource_read` —— 确认字符串资源语义（`license_expiry` = `"Date of expiry:"`）
- 混淆名反推路径（本次实际走的）：

```
ActivatedStateModel  (语义清晰的 model 类，未混淆)
  └─ xref new-instance → a.dp0.c(String)          ← 6 个分支 = 状态机
       └─ 读 a.t01.b() / a.cp0.c()
            └─ a.q50.A(String)                     ← 唯一的判决出口
```

**结论：混淆不可怕，从一个语义明确的数据模型出发，往上追生产者、往下追消费者，
两头一夹就出来了。**
