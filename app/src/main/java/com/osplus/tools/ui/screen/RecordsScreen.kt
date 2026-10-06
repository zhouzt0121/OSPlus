package com.osplus.tools.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.vm.DeviceViewModel

/**
 * 记录（一级页）：**录下来的一段会话**，以及事后分析。
 *
 * 职责边界（2.9.0 信息架构重构时划定）：这一页回答「刚才发生了什么」。
 *
 * 为什么把帧率与会话和电池会话放进同一个一级页：它们的本质完全相同——
 * 都是「用户主动开始 → 系统持续留档 → 停止后锁定供回看」。旧结构里
 * 「帧率」与「电源」各占一格底栏，但其中真正属于「实时状态」的部分
 * （当前帧率、当前电量）已经迁到总览与监测，剩下的一格里塞的其实是历史记录。
 * 把它们并成一页后，底栏腾出的一格给了「调优」。
 *
 * 两个子页签而不是一张混合列表：两者的数据模型差异很大——
 * 帧率会话是「每秒一条、含每核占用与功耗」的密集采样，
 * 电池会话是「每秒一条电量、含按应用归因」的稀疏采样，
 * 统计口径（方差/5% Low vs 平均功耗/理论续航）也完全不同。
 * 混在一张列表里只能用类型标签区分，点进去仍要两套详情页，
 * 反而增加一次「这行是哪种会话」的判断成本。
 */
@Composable
fun RecordsScreen(
    vm: DeviceViewModel,
    onOpenAnalysis: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 顶栏动作（复制 / 删除本次耗电记录）需要知道当前是不是电池会话页签，
    // 因此这个页签索引要同步回 ViewModel —— 根布局读的是 vm.onPowerSession。
    val vmTab by vm.recordsTab.collectAsStateWithLifecycle()

    // 以 ViewModel 为真值来源，本地状态只做首次同步，避免两处状态各自漂移。
    // 注意这里写的是 **recordsTab**（外层：帧率会话/电池会话），
    // 不是 powerTab（内层：耗电统计/充电统计）——两者语义不同，混用会让
    // 内层「耗电统计」页签的点击被这条同步逻辑覆盖掉（见 DeviceViewModel.recordsTab 注释）。
    if (vmTab != tab) {
        vm.setRecordsTab(tab)
    }

    Column(Modifier.fillMaxSize()) {
        LiquidNavTabs(
            items = listOf("帧率", "电池"),
            selectedIndex = tab,
            onSelect = {
                tab = it
                vm.setRecordsTab(it)
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        when (tab) {
            0 -> FpsRecordTab(vm, onOpenAnalysis)
            else -> PowerRecordTab(vm)
        }
    }
}
