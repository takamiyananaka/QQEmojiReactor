package com.emoji.reactor.hook;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 核心加载入口（高鲁棒性重构版）
 * 严格过滤仅在 QQ 聊天界面主进程运行，并支持多通道自适应生命周期注入
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "QQEmojiReactor";
    public static final String TARGET_PACKAGE = "com.tencent.mobileqq";
    private static final AtomicBoolean hasInitialized = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        String processName = lpparam.processName;
        // 严格仅在 QQ 聊天主进程运行，过滤一切子进程与辅助进程
        if (!TARGET_PACKAGE.equals(processName)) {
            return;
        }

        XposedBridge.log("[" + TAG + "] 成功载入目标主进程: " + processName);
        Log.i(TAG, "Loaded in process: " + processName);

        // 通道 1：如果此时已经可以加载类，直接尝试预加载
        tryInit(lpparam.classLoader);

        // 通道 2：标准 attachBaseContext 监听（标准 Android 生命周期，稳妥可靠）
        try {
            XposedHelpers.findAndHookMethod(
                    ContextWrapper.class,
                    "attachBaseContext",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.thisObject instanceof Application) {
                                Context ctx = (Context) param.args[0];
                                tryInit(ctx.getClassLoader());
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "Hook attachBaseContext 异常", t);
        }

        // 通道 3：Application#onCreate 兜底
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Application app = (Application) param.thisObject;
                            tryInit(app.getClassLoader());
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "Hook Application.onCreate 异常", t);
        }
    }

    private synchronized static void tryInit(ClassLoader cl) {
        if (cl == null) return;
        if (hasInitialized.get()) return;

        try {
            // 探针检查 QQ 核心类是否可见
            Class<?> aioMsgItemClass = XposedHelpers.findClassIfExists("com.tencent.mobileqq.aio.msg.AIOMsgItem", cl);
            if (aioMsgItemClass != null || hasInitialized.compareAndSet(false, true)) {
                hasInitialized.set(true);
                Log.i(TAG, "QQ 核心类已可见，开始安装菜单与贴表情探针");
                XposedBridge.log("[" + TAG + "] 探针正式装载就绪");

                MessageMenuHook.init(cl);
                QQKernelProbe.initProbe(cl);
                QQDynamicEmojiDumper.init(cl);
            }
        } catch (Throwable t) {
            Log.e(TAG, "tryInit 失败", t);
        }
    }
}
