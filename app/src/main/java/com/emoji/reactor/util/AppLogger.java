package com.emoji.reactor.util;

import android.util.Log;

/**
 * 统一防御性日志工具
 * 在 Android 运行时输出到 Logcat，在脱机测试或异常环境中安全回退，杜绝日志调用本身的崩溃
 */
public class AppLogger {

    public static void d(String tag, String msg) {
        try {
            Log.d(tag, msg);
        } catch (Throwable ignored) {
            System.out.println("[" + tag + "] [DEBUG] " + msg);
        }
    }

    public static void i(String tag, String msg) {
        try {
            Log.i(tag, msg);
        } catch (Throwable ignored) {
            System.out.println("[" + tag + "] [INFO] " + msg);
        }
    }

    public static void e(String tag, String msg, Throwable t) {
        try {
            Log.e(tag, msg, t);
        } catch (Throwable ignored) {
            System.err.println("[" + tag + "] [ERROR] " + msg + (t != null ? ": " + t.getMessage() : ""));
        }
    }
}
