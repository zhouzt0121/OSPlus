# OSPlus 性能调度 · 交付说明

本目录包含两条互相独立、可分别使用的交付线，用于控制 `Uperf Game Turbo` 与
`A-SOUL Games Optimization` 这两个性能调度模块：

| 交付线 | 产物 | 使用方式 |
| --- | --- | --- |
| **A. 模块自带 WebUI**（首选） | `Uperf_Game_Turbo-1.51-WebUI.zip`<br>`A-SOUL Games Optimization-Kana-WebUI.zip` | 直接在 root 管理器的模块页面里打开 WebUI 控制 |
| **B. OSPlus 统一控制**（备选） | `../OSPlus-1.5.0.apk` | 安装 APK，从「性能 → 性能调度」进入 |

两条线读写的是**同一份配置文件**，因此可以混用：在 WebUI 里改过的档位，OSPlus 刷新后同样可见，反之亦然。

---

## 一、产物清单

```
dist/
├── Uperf_Game_Turbo-1.51-WebUI.zip            1,988,498 B   154 文件
├── A-SOUL Games Optimization-Kana-WebUI.zip      35,740 B    11 文件
├── modules/
│   ├── uperf-webui/                           未打包的模块工作目录（含 webroot/）
│   └── asoul-webui/
├── pack_modules.py                            打包脚本
└── tools/
    └── smoke.js                               WebUI 冒烟测试
```

APK 位于仓库根目录：`OSPlus-1.5.0.apk`
（4,452,809 B · `com.osplus.tools` · versionCode 7 / versionName 1.5.0 · minSdk 33 / targetSdk 36）

---

## 二、A 线：模块 WebUI

### 2.1 刷入

两个 zip 都是**完整可刷模块**，不是补丁包——已在原模块基础上追加 `webroot/` 目录，
其余文件（`bin/`、`script/`、`service.sh`、`module.prop` 等）与原包一致。

1. 在 KernelSU / KernelSU-Next / APatch / MMRL 中直接安装对应 zip；
2. 安装完成后，模块条目会出现「打开」/「WebUI」入口，点击即进入控制面板；
3. **无需**手动设置 `webroot` 的权限或 SELinux 上下文——管理器安装时会自动处理，手动改动反而会导致打不开。

> `module.prop` 的 `name` / `version` 尾部追加了 ` · WebUI` 标识，便于与原版区分；
> `updateJson` 保持原地址不变，因此后续仍能收到上游更新提示。

### 2.2 Uperf 控制面板（`Uperf_Game_Turbo-1.51-WebUI.zip`）

面板分为四块：

- **守护进程** — 显示平台配置、当前电源模式、已配置规则数；
- **电源模式** — 六档即时切换：`省电 / 均衡 / 性能 / 极速 / 自动 / 疯狂`；
- **分应用模式** — 按包名增删改规则，支持 `*`（默认）与 `-`（息屏）两个特殊标记；
- **运行日志** — 读取末 150 行，另有「重启 uperf 进程」按钮。

实现要点（与模块自带脚本严格对齐）：

- 档位写入 `switcher.switchInode` = `/data/media/0/Android/yc/uperf/cur_powermode.txt`，
  同时写 `/sdcard/...` 这份 FUSE 映射，**双路径写入**以确保 uperf 的 inotify 一定被触发；
- 写后立即回读校验，失败会在页面顶部弹出提示；
- 合法档位取值直接来自模块内 `script/powercfg_main.sh` 的 `case` 分支，
  额外暴露 `pedestal → crazy` 档；**未凭经验臆造档位名**；
- 「重启 uperf 进程」**刻意不调用** `script/initsvc.sh`——该脚本中 `uperf_start()` 是
  **前台执行** `$BIN_PATH/uperf`，同步调用会永久挂起并拉起第二个实例。
  面板改为自行分离重启：`killall uperf` → `cd $MODDIR/bin` → `nohup ./uperf ... &`。

### 2.3 A-SOUL 控制面板（`A-SOUL Games Optimization-Kana-WebUI.zip`）

- **守护进程** — 显示配置文件路径、配置状态、分游戏规则数；
- **运行模式** — 三选一：`0 硬亲和 / 1 软迁移 / 2 硬迁移`；
- **实时模式** — `rt=0 / 1` 开关；
- **分游戏覆盖** — 每行「包名 + 模式 + 实时」两段式下拉，保存后即时应用；
- **恢复默认** / **重启 AsoulOpt**。

配置文件为 `/data/adb/naki/asopt.conf`，字段与上游 `service.sh` 解析逻辑一致。
与 uperf 不同，AsoulOpt 的启动行是 `nohup $MODDIR/AsoulOpt &`（已后台化），
因此重启可以安全地同步调用。

### 2.4 兼容性

`webroot` 内的 `core.js` 内置多管理器回退链：

```js
window.ksu  →  window.$ksu  →  window.mmrl  →  window.$
```

只要其中任意一个提供 `exec(command, options, callbackName)` 即可工作。
官方 `kernelsu` npm 包是 ESM 模块，`webroot` 不经打包无法 `import`，
因此面板直接对接注入的全局对象，零依赖、零构建步骤。

---

## 三、B 线：OSPlus 1.5.0

### 3.1 入口

安装 `OSPlus-1.5.0.apk` → 底部「性能」页 → 调优入口中的 **「性能调度」** → 进入二级页。

### 3.2 页面结构

二级页包含：`UperfStatusCard`（状态）/ `UperfModeCard`（档位）/ `PerAppCard`（分应用规则）/
`AsoulCard`（A-SOUL 全局与分游戏）/ `ServiceCard`（日志与重启）。
规则的增删改用**卡内展开**而非弹窗；选择应用时使用 `AppPicker`，基于 `BasicTextField`
自带搜索，无需额外的包名输入。

### 3.3 实现说明

- 数据源集中在 `core/PerfSchedDataSource.kt`（约 340 行），
  与 WebUI 版读写同一批文件，语义完全一致；
- 首屏用**一次合并 root 读取**（`##段名` 标记 + `parseSections`）替代逐字段 `su` 调用，
  显著降低启动开销；
- 写文件统一走 base64 中转，保证 UTF-8 安全。

---

## 四、已知限制

1. **未做真机验证。** 交付时无可用测试设备，全部验证止于静态与模拟层面（见第五节）。
   首次刷入 / 安装后请在设备上确认档位切换与规则写回是否符合预期。
2. **需要 root。** 两条线都依赖 root 读写系统路径，非 root 环境无法工作。
3. **Uperf 的 `fast` / `crazy` 档发热明显**，面板已做文字提示，实际散热条件由用户自行判断。
4. **A-SOUL 实时模式（`rt=1`）存在卡死风险**，仅建议在确认稳定的机型上开启。
5. WebUI 依赖管理器的 JS 桥接；极少数管理器版本未注入 `ksu` 对象时，
   页面会显示环境提示横幅而非空白（已针对该场景做骨架预渲染）。
6. 分应用规则匹配由模块自身完成，OSPlus / WebUI 只负责写入配置文件，
   规则是否命中取决于模块的实现细节。

---

## 五、已验证范围

以下为**实际执行过**的检查，非推断：

| 检查项 | 方法 | 结果 |
| --- | --- | --- |
| WebUI JS 语法 | 解析 `core.js` / `app.js` | 通过 |
| DOM id 一致性 | 比对 `index.html` 与 `Util.el()` 引用（uperf 16 个 / asoul 17 个） | 全部匹配 |
| 无桥接场景渲染 | `tools/smoke.js`（自建 DOM 垫片 + `vm.runInContext`） | 通过，骨架正常渲染不空白 |
| 有桥接场景渲染 | 同上，注入模拟 `ksu` 对象 | 通过 |
| zip 完整性 | 重新解包核对文件数与权限位（0755 / 0644） | 154 / 11 文件，一致 |
| APK 元信息 | `aapt2 dump badging` | `com.osplus.tools` v1.5.0 (7) |
| DEX 特征串 | 在 release DEX 中检索新增符号 | `setUperfMode`、`restartAsoul`、`perapp_powermode`、`asopt.conf`、`性能调度`、`硬亲和` 均已入包 |

**未执行**：真机功能验证、多管理器实机兼容性测试、长时间稳定性测试。

---

## 六、重新构建

```bash
# 重新打包两个模块（源目录已改名）
python dist/pack_modules.py

# WebUI 冒烟测试
node dist/tools/smoke.js

# 重新编译 APK
./gradlew :app:assembleRelease
```

`pack_modules.py` 会复刻原包目录结构并显式写入权限位
（原包权限位全为 `0o0`，直接复制会导致模块无法执行）。
