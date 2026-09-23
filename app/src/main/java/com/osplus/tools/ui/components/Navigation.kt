package com.osplus.tools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** 导航条目 */
data class BarItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * 顶栏：页面标题 + 实时摘要 + 右侧操作区。
 *
 * 去掉原先的背景模糊链路，改为实底表面 + 底部发丝线：
 * 滚动内容不会被半透明材质二次着色，文字对比度更稳定。
 * 传入 [onBack] 时左侧出现返回箭头（用于从概览下钻的详情页）。
 */
@Composable
fun OsTopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = osColors()
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.card)
                .statusBarsPadding()
                .padding(
                    start = if (onBack != null) 8.dp else 18.dp,
                    end = 12.dp,
                    top = 9.dp,
                    bottom = 9.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .pressable(onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = c.textSecondary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = OsText.pageTitle,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = it,
                        style = OsText.caption,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            actions()
        }
        Hairline()
    }
}

/** 顶栏右侧的圆形操作按钮 */
@Composable
fun OsActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    Box(
        modifier = modifier
            .padding(start = 6.dp)
            .size(34.dp)
            .clip(CircleShape)
            .background(c.cardAlt)
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = c.textSecondary,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * 底部悬浮导航。
 *
 * 独立胶囊容器悬浮于内容之上，选中项展开为「图标 + 文字」的品牌色胶囊，
 * 未选中项只保留图标；用实底 + 投影替代原方案的背景模糊，观感更利落。
 */
@Composable
fun OsFloatingBottomBar(
    items: List<BarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 64.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .osCard(RoundedCornerShape(30.dp), elevation = 12.dp)
                .padding(horizontal = 7.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                FloatingBarItem(
                    item = item,
                    selected = index == selectedIndex,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun FloatingBarItem(
    item: BarItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = osColors()
    val highlight by animateColorAsState(
        targetValue = if (selected) c.primary.copy(alpha = 0.14f) else Color.Transparent,
        label = "barHighlight",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) c.primary else c.textSecondary,
        label = "barContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = spring(),
        label = "barScale",
    )
    val capsuleWidth by animateDpAsState(
        targetValue = if (selected) 96.dp else 44.dp,
        label = "barWidth",
    )

    Box(
        modifier = modifier.padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .width(capsuleWidth)
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(highlight)
                .scale(scale)
                .pressable(onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            if (selected) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = item.label,
                    style = OsText.navLabel,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}
