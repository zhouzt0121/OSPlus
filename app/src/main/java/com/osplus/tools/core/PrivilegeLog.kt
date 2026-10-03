package com.osplus.tools.core

import android.util.Log

/**
 * 提权通道（Root / Shizuku / ADB）专用日志。
 *
 * 单独开一个而不是直接用 [Log]，有两个原因：
 *
 * 1. **提权问题几乎无法远程调试**。用户反馈「连不上」时唯一能拿到的证据就是
 *    日志，所以这几条通道的日志必须保证 release 包也输出，不能依赖 BuildConfig.DEBUG。
 *
 * 2. 统一前缀，方便用户按 `adb logcat -s OSPlusPriv` 一条命令捞全。
 */
internal object PrivilegeLog {

    /** 统一 tag，方便过滤 */
    private const val TAG = "OSPlusPriv"

    fun d(tag: String, msg: String) = Log.d(TAG, "[$tag] $msg")

    fun i(tag: String, msg: String) = Log.i(TAG, "[$tag] $msg")

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, "[$tag] $msg") else Log.w(TAG, "[$tag] $msg", t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, "[$tag] $msg") else Log.e(TAG, "[$tag] $msg", t)
    }
}
