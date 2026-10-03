package com.osplus.tools.ui.screen

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.AsoulGame
import com.osplus.tools.core.AsoulState
import com.osplus.tools.core.PerAppRule
import com.osplus.tools.core.PerfSchedDataSource
import com.osplus.tools.core.UperfState
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.ChoiceChip
import com.osplus.tools.ui.components.Hairline
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.glassSurface
import com.osplus.tools.ui.components.pressable
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.osplus.tools.ui.components.bottomBarContentPadding
import top.yukonga.miuix.kmp.basic.Text

/** 应用列表项：包名 + 显示名 */
private data class AppEntry(val pkg: String, val label: String)

/** 特殊规则（非包名）在界面上的显示名 */
private fun ruleTitle(target: String): String = when (target) {
    "*" -> "默认规则"
    "-" -> "息屏时"
    else -> target
}

private fun isSpecialRule(target: String): Boolean = target == "*" || target == "-"

/**
 * 性能调度（二级页）：控制 Uperf Game Turbo 与 A-SOUL Games Optimization 两个模块。
 *
 * 这两个模块各自以「配置文件 + 常驻进程」的方式工作，本页读写的正是模块自己用的那份配置，
 * 因此与模块自带的 WebUI、以及模块自身的脚本完全兼容，不会出现两套状态互相覆盖。
 *
 * 交互上刻意不用弹窗：应用选择器以「卡内展开」的方式呈现。
 * 一是避免为两个模块各写一套对话框，二是展开后仍能看到已配置的规则，
 * 添加时不必先记住列表里已经有什么。
 */
@Composable
fun PerfSchedScreen(vm: DeviceViewModel) {
    val uperf by vm.uperfState.collectAsStateWithLifecycle()
    val asoul by vm.asoulState.collectAsStateWithLifecycle()
    val notice by vm.schedNotice.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val refreshing by vm.schedRefreshing.collectAsStateWithLifecycle()
    // 性能调度读写的是 /data/adb 下的 Magisk 模块文件，**只有 Root 能读**
    // （Shizuku / ADB 都是 shell 域）。因此门控用能力表而不是 rootAvailable：
    // 前者能区分「没提权」和「提权了但身份不够」这两种完全不同的原因。
    val caps by vm.capabilities.collectAsStateWithLifecycle()
    val schedEnabled = caps.canControlPerfSched

    LaunchedEffect(Unit) { vm.refreshPerfSched() }

    // 操作回执展示 4 秒后自动收起，避免长期占据页面顶部
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            vm.clearSchedNotice()
        }
    }

    val apps = rememberInstalledApps()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!schedEnabled) {
            item { NoticeBanner(caps.reasonForPerfSched()) }
        }
        notice?.let { item { NoticeBanner(it) } }

        item { UperfStatusCard(uperf, refreshing) }
        item { UperfModeCard(uperf, enabled = schedEnabled, onPick = vm::setUperfMode) }
        item {
            PerAppCard(
                rules = uperf.rules,
                apps = apps,
                enabled = schedEnabled,
                onSave = vm::setUperfPerAppRules,
            )
        }
        item {
            AsoulCard(
                state = asoul,
                apps = apps,
                enabled = schedEnabled,
                onSave = vm::setAsoulConfig,
            )
        }
        item {
            ServiceCard(
                onRestartUperf = vm::restartUperf,
                onRestartAsoul = vm::restartAsoul,
                onRefresh = vm::refreshPerfSched,
                uperfInstalled = uperf.installed,
                asoulInstalled = asoul.installed,
                enabled = schedEnabled,
            )
        }
    }
}

/* ==================================================================== Uperf */

@Composable
private fun UperfStatusCard(uperf: UperfState, refreshing: Boolean) {
    val c = osColors()
    SectionCard {
        CardSectionLabel("Uperf Game Turbo")
        Spacer(Modifier.height(6.dp))
        if (!uperf.installed) {
            Text(
                text = if (refreshing) "正在检测…" else "未检测到模块，请先刷入 Uperf Game Turbo",
                style = OsText.caption,
                color = c.textTertiary,
            )
            return@SectionCard
        }
        InfoRow("守护进程", if (uperf.running) "运行中 · PID ${uperf.pid}" else "未运行")
        InfoRow("平台配置", uperf.socName.ifBlank { "未识别" })
        InfoRow(
            label = "当前档位",
            value = uperf.mode.ifBlank { "未读取到" }.let { PerfSchedDataSource.uperfModeLabel(it) },
            emphasis = true,
        )
    }
}

@Composable
private fun UperfModeCard(
    uperf: UperfState,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    val c = osColors()
    SectionCard {
        CardSectionLabel("电源档位")
        Spacer(Modifier.height(10.dp))
        ChipGrid(
            options = PerfSchedDataSource.UPERF_MODES,
            selected = uperf.mode,
            onPick = onPick,
            enabled = enabled,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "写入 uperf 监听的 cur_powermode.txt，切换即时生效，无需重启。" +
                "极速与疯狂档会显著提升发热，请自行确认散热条件。",
            style = OsText.caption,
            color = c.textTertiary,
        )
    }
}

/* ================================================================= 分应用规则 */

@Composable
private fun PerAppCard(
    rules: List<PerAppRule>,
    apps: List<AppEntry>,
    enabled: Boolean,
    onSave: (List<PerAppRule>) -> Unit,
) {
    val c = osColors()
    val labelOf = remember(apps) { apps.associate { it.pkg to it.label } }
    var expanded by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    SectionCard {
        CardSectionLabel("分应用模式")
        Spacer(Modifier.height(2.dp))
        Text(
            text = "前台命中即切换档位",
            style = OsText.micro,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(8.dp))

        if (rules.isEmpty()) {
            Text(
                text = "暂无规则，所有应用使用上面的全局档位",
                style = OsText.caption,
                color = c.textTertiary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        rules.forEachIndexed { index, rule ->
            val key = "up-$index-${rule.target}"
            Hairline(verticalPadding = 0.dp)
            RuleRow(
                title = if (isSpecialRule(rule.target)) ruleTitle(rule.target) else
                    (labelOf[rule.target] ?: rule.target),
                subtitle = if (isSpecialRule(rule.target)) {
                    if (rule.target == "*") "未命中的应用" else "offscreen"
                } else {
                    rule.target
                },
                modeLabel = PerfSchedDataSource.uperfModeLabel(rule.mode),
                options = PerfSchedDataSource.PER_APP_MODES
                    .map { it to PerfSchedDataSource.uperfModeLabel(it) },
                selectedMode = rule.mode,
                expanded = expanded == key,
                onToggle = { expanded = if (expanded == key) null else key },
                onPickMode = { mode ->
                    onSave(rules.toMutableList().also { it[index] = rule.copy(mode = mode) })
                    expanded = null
                },
                onDelete = if (isSpecialRule(rule.target)) null else {
                    { onSave(rules.toMutableList().also { it.removeAt(index) }) }
                },
                enabled = enabled,
            )
        }

        Hairline(verticalPadding = 0.dp)
        ActionRow(
            text = if (picking) "收起应用列表" else "＋ 添加应用",
            accent = true,
            enabled = enabled,
            onClick = { picking = !picking },
        )

        if (picking) {
            Spacer(Modifier.height(8.dp))
            AppPicker(
                apps = apps,
                exclude = rules.map { it.target }.toSet(),
                onPick = { entry ->
                    onSave(rules + PerAppRule(entry.pkg, "performance"))
                    picking = false
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "「*」为默认规则（未命中的应用），「-」为息屏规则。保存后立即写回 " +
                "perapp_powermode.txt，切换应用时生效。",
            style = OsText.caption,
            color = c.textTertiary,
        )
    }
}

/* =================================================================== A-SOUL */

@Composable
private fun AsoulCard(
    state: AsoulState,
    apps: List<AppEntry>,
    enabled: Boolean,
    onSave: (String, String, List<AsoulGame>) -> Unit,
) {
    val c = osColors()
    val labelOf = remember(apps) { apps.associate { it.pkg to it.label } }
    var expanded by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    SectionCard {
        CardSectionLabel("A-SOUL Games Optimization")
        Spacer(Modifier.height(6.dp))
        if (!state.installed) {
            Text(
                text = "未检测到模块，请先刷入 A-SOUL Games Optimization",
                style = OsText.caption,
                color = c.textTertiary,
            )
            return@SectionCard
        }

        InfoRow("守护进程", if (state.running) "运行中 · PID ${state.pid}" else "未运行")
        InfoRow(
            label = "当前模式",
            value = "${PerfSchedDataSource.asoulModeLabel(state.mode)} / " +
                "rt ${PerfSchedDataSource.asoulRtLabel(state.rt)}",
            emphasis = true,
        )

        Spacer(Modifier.height(12.dp))
        CardSectionLabel("运行模式")
        Spacer(Modifier.height(8.dp))
        ChipGrid(
            options = PerfSchedDataSource.ASOUL_MODES,
            selected = state.mode,
            onPick = { onSave(it, state.rt, state.games) },
            enabled = enabled,
        )

        Spacer(Modifier.height(12.dp))
        CardSectionLabel("实时模式")
        Spacer(Modifier.height(8.dp))
        ChipGrid(
            options = PerfSchedDataSource.ASOUL_RTS,
            selected = state.rt,
            onPick = { onSave(state.mode, it, state.games) },
            columns = 2,
            enabled = enabled,
        )

        Spacer(Modifier.height(12.dp))
        CardSectionLabel("分游戏覆盖")
        Spacer(Modifier.height(2.dp))
        Text(
            text = "未匹配的游戏使用上面的全局值",
            style = OsText.micro,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(8.dp))

        if (state.games.isEmpty()) {
            Text(
                text = "暂无分游戏规则",
                style = OsText.caption,
                color = c.textTertiary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        state.games.forEachIndexed { index, game ->
            val key = "as-$index-${game.pkg}"
            Hairline(verticalPadding = 0.dp)
            RuleRow(
                title = labelOf[game.pkg] ?: game.pkg,
                subtitle = "${game.pkg} · rt ${PerfSchedDataSource.asoulRtLabel(game.rt)}",
                modeLabel = PerfSchedDataSource.asoulModeLabel(game.mode),
                options = PerfSchedDataSource.ASOUL_MODES,
                selectedMode = game.mode,
                expanded = expanded == key,
                onToggle = { expanded = if (expanded == key) null else key },
                onPickMode = { mode ->
                    onSave(
                        state.mode,
                        state.rt,
                        state.games.toMutableList().also { it[index] = game.copy(mode = mode) },
                    )
                    expanded = null
                },
                onDelete = {
                    onSave(
                        state.mode,
                        state.rt,
                        state.games.toMutableList().also { it.removeAt(index) },
                    )
                },
                enabled = enabled,
            )
        }

        Hairline(verticalPadding = 0.dp)
        ActionRow(
            text = if (picking) "收起游戏列表" else "＋ 添加游戏",
            accent = true,
            enabled = enabled,
            onClick = { picking = !picking },
        )

        if (picking) {
            Spacer(Modifier.height(8.dp))
            AppPicker(
                apps = apps,
                exclude = state.games.map { it.pkg }.toSet(),
                onPick = { entry ->
                    onSave(state.mode, state.rt, state.games + AsoulGame(entry.pkg, state.mode, state.rt))
                    picking = false
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "保存后立即重启 AsoulOpt，切换游戏时生效。开启实时模式可能更流畅，" +
                "但存在卡死风险，建议确认稳定后再长期使用。",
            style = OsText.caption,
            color = c.textTertiary,
        )
    }
}

/* =============================================================== 服务操作 */

@Composable
private fun ServiceCard(
    onRestartUperf: () -> Unit,
    onRestartAsoul: () -> Unit,
    onRefresh: () -> Unit,
    uperfInstalled: Boolean,
    asoulInstalled: Boolean,
    enabled: Boolean,
) {
    val c = osColors()
    SectionCard {
        CardSectionLabel("服务操作")
        Spacer(Modifier.height(8.dp))
        ActionRow(
            text = "重启 uperf 进程",
            enabled = uperfInstalled && enabled,
            onClick = onRestartUperf,
        )
        Hairline(verticalPadding = 0.dp)
        ActionRow(
            text = "重启 AsoulOpt",
            enabled = asoulInstalled && enabled,
            onClick = onRestartAsoul,
        )
        Hairline(verticalPadding = 0.dp)
        ActionRow(
            // 「重新读取状态」始终可点：它只是重读，不写任何文件，
            // 即便当前模式读不到内容，重读也是排查问题的第一步
            text = "重新读取状态",
            onClick = onRefresh,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "重启只针对模块的常驻进程，不改变已写入的配置，也不会重复执行开机时的" +
                "系统统一化步骤。",
            style = OsText.caption,
            color = c.textTertiary,
        )
    }
}

/* =============================================================== 通用小组件 */

/** 三列/两列胶囊网格：补齐空位，避免最后一行被拉伸 */
@Composable
private fun ChipGrid(
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    columns: Int = 3,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, label) ->
                    ChoiceChip(
                        text = label,
                        selected = id == selected,
                        enabled = enabled,
                        // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                        onClick = { if (enabled) onPick(id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 规则行：点击整行展开档位胶囊。
 *
 * 展开式而非弹窗式，一是与页面其余部分的交互语言一致，
 * 二是展开后仍能同时看到其他规则，便于横向比较。
 */
@Composable
private fun RuleRow(
    title: String,
    subtitle: String,
    modeLabel: String,
    options: List<Pair<String, String>>,
    selectedMode: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPickMode: (String) -> Unit,
    onDelete: (() -> Unit)?,
    enabled: Boolean = true,
) {
    val c = osColors()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .then(if (enabled) Modifier.pressable(onToggle) else Modifier)
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = OsText.value,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = OsText.micro,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = modeLabel,
                style = OsText.caption,
                color = c.primary,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            if (enabled) {
                Text(
                    text = if (expanded) "⌃" else "⌄",
                    style = OsText.caption,
                    color = c.textTertiary,
                )
            }
            if (onDelete != null && enabled) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "✕",
                    style = OsText.label,
                    color = c.textTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .pressable(onDelete)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }

        if (expanded && enabled) {
            // 展开的面板是一块**凹进去**的玻璃：elevation = 0，不加投影。
            // 它是从行内「沉」下去的一块，不是浮起来的一张卡——
            // 凹槽与凸片是同一块玻璃的两种受力状态，这条规则和分段控件保持一致。
            //
            // 用静态光学而非实时折射：它内联在卡片内部，背后就是卡片本身，
            // 折射一片纯色卡片得到的还是纯色，白付性能。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .glassSurface(
                        shape = RoundedCornerShape(12.dp),
                        cornerRadius = 12.dp,
                        body = c.cardAlt,
                        concave = true,
                    )
                    .padding(10.dp)
            ) {
                ChipGrid(
                    options = options,
                    selected = selectedMode,
                    onPick = onPickMode,
                )
            }
        }
    }
}

/** 行内动作：用于「添加应用」「重启服务」这类单行操作 */
@Composable
private fun ActionRow(
    text: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    val c = osColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行内动作原本没有任何底色，只有一圈点击区；
            // 换成玻璃表面后它才是一个「看得见的按钮」，而不是一段可点的文字
            .glassSurface(
                shape = RoundedCornerShape(10.dp),
                cornerRadius = 10.dp,
                body = c.cardAlt,
                elevation = 1.dp,
            )
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = OsText.value,
            color = when {
                !enabled -> c.textTertiary
                accent -> c.primary
                else -> c.textPrimary
            },
        )
    }
}

/**
 * 应用选择器：搜索框 + 过滤后的应用列表。
 *
 * 结果上限 40 条。这里没有做列表虚拟化——它嵌在 LazyColumn 的一个 item 里，
 * 但用户一进来通常就带搜索词，40 条的渲染代价可以忽略。
 */
@Composable
private fun AppPicker(
    apps: List<AppEntry>,
    exclude: Set<String>,
    onPick: (AppEntry) -> Unit,
) {
    val c = osColors()
    var keyword by remember { mutableStateOf("") }

    val filtered = remember(apps, exclude, keyword) {
        val k = keyword.trim().lowercase()
        apps.asSequence()
            .filter { it.pkg !in exclude }
            .filter {
                k.isEmpty() || it.pkg.lowercase().contains(k) || it.label.lowercase().contains(k)
            }
            .take(40)
            .toList()
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.cardAlt)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (keyword.isEmpty()) {
                Text(
                    text = "搜索应用名或包名…",
                    style = OsText.value,
                    color = c.textTertiary,
                )
            }
            BasicTextField(
                value = keyword,
                onValueChange = { keyword = it },
                singleLine = true,
                textStyle = OsText.value.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(6.dp))

        if (apps.isEmpty()) {
            Text(
                text = "正在读取应用列表…",
                style = OsText.caption,
                color = c.textTertiary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else if (filtered.isEmpty()) {
            Text(
                text = "没有匹配的应用",
                style = OsText.caption,
                color = c.textTertiary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            filtered.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .pressable { onPick(entry) }
                        .padding(vertical = 9.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.label,
                            style = OsText.value,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = entry.pkg,
                            style = OsText.micro,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 读取已安装的第三方应用（带显示名），供选择器使用 */
@Composable
private fun rememberInstalledApps(): List<AppEntry> {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                    .sortedBy { it.label }
                    .toList()
            }.getOrDefault(emptyList())
        }
    }
    return apps
}
