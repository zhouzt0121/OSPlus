package com.osplus.tools.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 可选的主题模式（对外暴露的简化集合） */
enum class AppThemeMode(val label: String, val miuixMode: ColorSchemeMode, val monetMode: ColorSchemeMode) {
    System("跟随系统", ColorSchemeMode.System, ColorSchemeMode.MonetSystem),
    Light("浅色", ColorSchemeMode.Light, ColorSchemeMode.MonetLight),
    Dark("深色", ColorSchemeMode.Dark, ColorSchemeMode.MonetDark),
}

/**
 * 应用主题。
 *
 * 默认使用中性配色（不取壁纸色），避免发灰与对比度不足，保证大量数据场景的可读性；
 * 用户可在「设置」中切换深浅色或开启壁纸取色。
 */
@Composable
fun OSPlusTheme(
    mode: AppThemeMode = AppThemeMode.System,
    monet: Boolean = false,
    content: @Composable () -> Unit,
) {
    val controller = remember(mode, monet) {
        ThemeController(if (monet) mode.monetMode else mode.miuixMode)
    }
    MiuixTheme(controller = controller, content = content)
}
