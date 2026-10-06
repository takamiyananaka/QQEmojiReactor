package com.emoji.reactor.hook;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.emoji.reactor.util.AppLogger;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 核心加载入口（高鲁棒性重构版）
 * 严格过滤仅在 QQ 聊天界面主进程运行，并支持多通道自适应生命周期注入与配置热重载
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "QQEmojiReactor";
    public static final String TARGET_PACKAGE = "com.tencent.mobileqq";
    public static final String QZONE_PROCESS = "com.tencent.mobileqq:qzone";
    private static final AtomicBoolean hasInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean receiverRegistered = new AtomicBoolean(false);
    private static volatile Context sAppContext = null;
    private static volatile ClassLoader sClassLoader = null;
    private static volatile String sCurrentProcessName = "";
    private static volatile boolean sIsQzoneProc = false;

    public static boolean isQzoneProcess() {
        return sIsQzoneProc;
    }

    public static void setAppContext(Context ctx) {
        if (ctx == null) return;
        sAppContext = ctx;
        registerConfigReceiver(ctx);
        UidUinHelper.warmUpConfiguredUins();
    }

    public static ClassLoader getClassLoader() {
        return sClassLoader;
    }

    public static Context getAppContext() {
        if (sAppContext != null) return sAppContext;

        ClassLoader cl = sClassLoader;
        if (cl != null) {
            // 兜底方案 1: 从 MobileQQ.getContext() 反射获取
            try {
                Class<?> mqq = XposedHelpers.findClassIfExists("mqq.app.MobileQQ", cl);
                if (mqq != null) {
                    Method m = mqq.getMethod("getContext");
                    Object ctx = m.invoke(null);
                    if (ctx instanceof Context) {
                        setAppContext((Context) ctx);
                        return sAppContext;
                    }
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "从MobileQQ获取Context安全跳过: " + e.getMessage());
            }

            // 兜底方案 2: 从 BaseApplication.getContext() 反射获取
            try {
                Class<?> baseApp = XposedHelpers.findClassIfExists("com.tencent.qphone.base.util.BaseApplication", cl);
                if (baseApp != null) {
                    Method m = baseApp.getMethod("getContext");
                    Object ctx = m.invoke(null);
                    if (ctx instanceof Context) {
                        setAppContext((Context) ctx);
                        return sAppContext;
                    }
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "从BaseApplication获取Context安全跳过: " + e.getMessage());
            }
        }

        return null;
    }

    private static void registerConfigReceiver(Context ctx) {
        if (ctx == null) return;
        if (!receiverRegistered.compareAndSet(false, true)) return;

        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null && com.emoji.reactor.data.ConfigManager.ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                        com.emoji.reactor.util.AppLogger.i("CustomAvatarFeature", "📢 收到配置与开关变动广播，秒级热重载 QQ 进程配置...");
                        // 1. 先彻底清空旧位图缓存与旧映射
                        com.emoji.reactor.feature.impl.CustomAvatarFeature.clearCircleCache();
                        RemoteConfigHelper.invalidateCache();
                        // 2. 刷新模块开关状态
                        RemoteConfigHelper.isModuleEnabled(context);
                        // 3. 解析最新广播或穿透携带的最新自定义头像配置并写盘
                        String customAvatarsJson = intent.getStringExtra(com.emoji.reactor.data.ConfigManager.KEY_CUSTOM_AVATARS);
                        boolean parsed = false;
                        if (customAvatarsJson != null && !customAvatarsJson.trim().isEmpty()) {
                            parsed = RemoteConfigHelper.parseCustomAvatarsJson(customAvatarsJson);
                        }
                        if (!parsed) {
                            RemoteConfigHelper.reloadFullConfig(context);
                        }
                        // 4. 主动预热双向 UID<->UIN 映射
                        UidUinHelper.warmUpConfiguredUins();
                        // 5. 刷新气泡徽标
                        com.emoji.reactor.feature.helper.BubbleBadgeHelper.refreshAllVisibleBadges();
                        // 6. 活跃界面免重启即时热刷新当前可见头像
                        com.emoji.reactor.feature.impl.CustomAvatarFeature.refreshVisibleAvatarsOnForeground();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(com.emoji.reactor.data.ConfigManager.ACTION_CONFIG_CHANGED);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Context.RECEIVER_EXPORTED == 2
                ctx.registerReceiver(receiver, filter, 2);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            com.emoji.reactor.util.AppLogger.i("CustomAvatarFeature", "已成功在 QQ 进程注册配置变更动态广播监听！");
        } catch (Throwable t) {
            receiverRegistered.set(false);
            Log.e(TAG, "注册配置变更广播异常", t);
        }
    }

    private static final java.util.Set<ClassLoader> sHookedClassLoaders =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<ClassLoader, Boolean>()));
    private static final ThreadLocal<Boolean> sIsLoadingClass = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        String processName = lpparam.processName;
        boolean isMainProc = TARGET_PACKAGE.equals(processName);
        boolean isSubUiProc = processName.startsWith("com.tencent.mobileqq:") && !processName.endsWith(":MSF") && !processName.endsWith(":video");

        // 在 QQ 聊天主进程与涉及 UI 渲染的子进程 (如 :qzone, :tool) 中放行运行
        if (!isMainProc && !isSubUiProc) {
            return;
        }

        sCurrentProcessName = processName;
        sIsQzoneProc = isSubUiProc;
        sClassLoader = lpparam.classLoader;
        if (lpparam.classLoader != null) {
            sHookedClassLoaders.add(lpparam.classLoader);
        }

        XposedBridge.log("[" + TAG + "] 成功载入目标进程: " + processName);
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
                                sAppContext = ctx;
                                registerConfigReceiver(ctx);
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
                            setAppContext(app);
                            tryInit(app.getClassLoader());
                            if (!hasInitialized.get() && hasInitialized.compareAndSet(false, true)) {
                                Log.i(TAG, "Application.onCreate 兜底触发插件初始化 (" + sCurrentProcessName + ")");
                                if (sIsQzoneProc) {
                                    new com.emoji.reactor.feature.impl.CustomAvatarFeature().onInit(app.getClassLoader());
                                } else {
                                    com.emoji.reactor.feature.FeatureManager.initAllFeatures(app, app.getClassLoader());
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "Hook Application.onCreate 异常", t);
        }

        // 通道 4：QBaseActivity.onCreate 动态捕获并注入 Context（QAuxiliary 业界成熟方案）
        try {
            Class<?> qActivityClass = XposedHelpers.findClassIfExists("com.tencent.mobileqq.app.QBaseActivity", lpparam.classLoader);
            if (qActivityClass != null) {
                XposedBridge.hookAllMethods(qActivityClass, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof Context) {
                            setAppContext(((Context) param.thisObject).getApplicationContext());
                        }
                    }
                });
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "hook QBaseActivity.onCreate 安全跳过: " + e.getMessage());
        }

        // 通道 5：监听 QQ 插件系统 PluginStatic 动态加载 (精准捕获 qzone_plugin.apk ClassLoader)
        try {
            Class<?> pluginStaticClass = XposedHelpers.findClassIfExists("com.tencent.mobileqq.pluginsdk.PluginStatic", lpparam.classLoader);
            if (pluginStaticClass != null) {
                XC_MethodHook pluginLoaderHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (result instanceof ClassLoader) {
                            onDynamicClassLoaderLoaded((ClassLoader) result);
                        }
                    }
                };
                for (Method m : pluginStaticClass.getDeclaredMethods()) {
                    if (m.getName().startsWith("getOrCreateClassLoader")) {
                        XposedBridge.hookMethod(m, pluginLoaderHook);
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "hook PluginStatic 安全跳过: " + t.getMessage());
        }

        // 通道 6：通用动态 BaseDexClassLoader 实例化监听 (动态捕获所有插件与分包 DEX)
        try {
            XposedBridge.hookAllConstructors(dalvik.system.BaseDexClassLoader.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof ClassLoader) {
                        onDynamicClassLoaderLoaded((ClassLoader) param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            AppLogger.d(TAG, "hook BaseDexClassLoader 安全跳过: " + t.getMessage());
        }
    }

    public static void onDynamicClassLoaderLoaded(ClassLoader cl) {
        if (cl == null || !sHookedClassLoaders.add(cl)) return;
        try {
            com.emoji.reactor.feature.impl.CustomAvatarFeature.onDynamicClassLoaderLoaded(cl);
        } catch (Throwable t) {
            AppLogger.d(TAG, "onDynamicClassLoaderLoaded 安全跳过: " + t.getMessage());
        }
    }

    private synchronized static void tryInit(ClassLoader cl) {
        if (cl == null || hasInitialized.get()) return;

        try {
            // 探针检查当前进程的核心业务类是否已在此 ClassLoader 中可见
            boolean ready = false;
            if (sIsQzoneProc) {
                ready = XposedHelpers.findClassIfExists("com.tencent.image.URLDrawable", cl) != null
                        || XposedHelpers.findClassIfExists("cooperation.qzone.util.QZoneFaceUtils", cl) != null
                        || XposedHelpers.findClassIfExists("com.tencent.qzone.image.ImageLoader", cl) != null
                        || XposedHelpers.findClassIfExists("com.tencent.libra.request.Option", cl) != null;
            } else {
                ready = XposedHelpers.findClassIfExists("com.tencent.mobileqq.aio.msg.AIOMsgItem", cl) != null
                        || XposedHelpers.findClassIfExists("com.tencent.mobileqq.proavatar.QQProAvatarView", cl) != null;
            }

            if (ready && hasInitialized.compareAndSet(false, true)) {
                Log.i(TAG, "QQ 核心类已可见，开始分发初始化功能特征插件 (" + sCurrentProcessName + ")");
                XposedBridge.log("[" + TAG + "] 功能引擎正式装载就绪 (" + sCurrentProcessName + ")");

                Context ctx = getAppContext();
                if (ctx != null) {
                    registerConfigReceiver(ctx);
                }

                if (sIsQzoneProc) {
                    new com.emoji.reactor.feature.impl.CustomAvatarFeature().onInit(cl);
                } else {
                    com.emoji.reactor.feature.FeatureManager.initAllFeatures(ctx, cl);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "tryInit 失败", t);
        }
    }
}
