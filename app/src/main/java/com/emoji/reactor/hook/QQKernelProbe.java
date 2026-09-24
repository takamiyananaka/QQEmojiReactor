package com.emoji.reactor.hook;

import com.emoji.reactor.util.AppLogger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * QQ NT 内核服务探针（全链路穿透版）
 */
public class QQKernelProbe {

    private static final String TAG = "QQEmojiReactor_Kernel";

    private static Object cachedMsgService;
    private static ClassLoader hostClassLoader;

    public static void initProbe(ClassLoader cl) {
        hostClassLoader = cl;
        if (cl == null) return;

        // 1. 尝试直接主动抓取 MsgService
        getOrFindKernelService(cl);

        // 2. 挂载被动捕获探针（当 QQ 内部执行任何消息操作时自动截获）
        hookKernelMsgService(cl);
    }

    private static void hookKernelMsgService(ClassLoader cl) {
        String[] candidateClasses = {
                "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService",
                "com.tencent.qqnt.msg.api.impl.MsgServiceImpl"
        };

        for (String className : candidateClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
                if (clazz == null) continue;

                // 监听所有构造函数
                XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject != null) {
                            registerMsgService(param.thisObject);
                        }
                    }
                });

                // 监听所有方法触发
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().contains("setMsgEmojiLike") || m.getName().equals("getService")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.thisObject != null) {
                                    registerMsgService(param.thisObject);
                                }
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public interface OnEmojiResultListener {
        void onResult(int code, String err);
    }

    public static void registerMsgService(Object service) {
        if (service != null) {
            cachedMsgService = service;
            AppLogger.i(TAG, "已成功捕获并锁定 MsgService 实例: " + service.getClass().getName());
        }
    }

    /**
     * 发送表情点赞核心方法（通用字符串 ID + 原生 Type 版）
     */
    public static boolean invokeSetMsgEmojiLike(Object msgRecord, String emojiIdStr, long emojiType, OnEmojiResultListener resultListener) {
        if (msgRecord == null || emojiIdStr == null || emojiIdStr.trim().isEmpty()) {
            AppLogger.e(TAG, "invokeSetMsgEmojiLike 失败: msgRecord 或 emojiId 为空", null);
            return false;
        }

        try {
            ClassLoader cl = hostClassLoader != null ? hostClassLoader : msgRecord.getClass().getClassLoader();
            Object kernelService = getOrFindKernelService(cl);
            if (kernelService == null) {
                AppLogger.e(TAG, "未捕获到 IKernelMsgService 实例，无法发送", null);
                return false;
            }

            // 1. 提取消息信息
            long msgSeq = extractMsgSeq(msgRecord);
            int chatType = extractIntField(msgRecord, "chatType", 2);
            String peerUid = extractStringField(msgRecord, "peerUid", "");
            String guildId = extractStringField(msgRecord, "guildId", "");

            if (msgSeq == 0) {
                AppLogger.e(TAG, "提取到的 msgSeq 为 0，可能并非合法的 MsgRecord 对象", null);
            }

            // 2. 构造 Contact 对象（双包名穿透兼容）
            Object contact = createContact(cl, chatType, peerUid, guildId);
            if (contact == null) {
                AppLogger.e(TAG, "构造 Contact 对象失败", null);
                return false;
            }

            // 3. 定位并调用 setMsgEmojiLikes
            Method targetMethod = findSetMsgEmojiLikesMethod(kernelService.getClass());
            if (targetMethod == null && cachedMsgService != null && cachedMsgService != kernelService) {
                targetMethod = findSetMsgEmojiLikesMethod(cachedMsgService.getClass());
                if (targetMethod != null) kernelService = cachedMsgService;
            }

            if (targetMethod != null) {
                targetMethod.setAccessible(true);
                Class<?>[] paramTypes = targetMethod.getParameterTypes();

                // 构造非空动态代理 Callback，杜绝底层 C++ JNI 报空指针丢包，并实时回调结果
                Object callbackProxy = createCallbackProxy(cl, paramTypes, resultListener);

                Object[] args = buildArgs(paramTypes, contact, msgSeq, emojiIdStr.trim(), emojiType, true, callbackProxy);
                targetMethod.invoke(kernelService, args);
                AppLogger.i(TAG, "成功触发底层 setMsgEmojiLikes (Type: " + emojiType + ", ID: " + emojiIdStr + ", msgSeq: " + msgSeq + ")");
                return true;
            } else {
                AppLogger.e(TAG, "未找到目标 setMsgEmojiLikes 方法在类: " + kernelService.getClass().getName(), null);
            }

        } catch (Throwable t) {
            AppLogger.e(TAG, "调用 setMsgEmojiLike 发生异常 (ID: " + emojiIdStr + ", Type: " + emojiType + ")", t);
        }

        return false;
    }

    /**
     * 发送表情点赞核心方法（兼容旧版整型 ID）
     */
    public static boolean invokeSetMsgEmojiLike(Object msgRecord, int faceId, OnEmojiResultListener resultListener) {
        long emojiType = (faceId < 1000) ? 1L : 2L;
        return invokeSetMsgEmojiLike(msgRecord, String.valueOf(faceId), emojiType, resultListener);
    }

    /**
     * 多通道主动抓取 IKernelMsgService
     */
    private static Object getOrFindKernelService(ClassLoader cl) {
        if (cachedMsgService != null) return cachedMsgService;

        // 通道 1：KernelServiceUtil (QQ NT 标准工具类)
        try {
            Class<?> ksuClass = XposedHelpers.findClassIfExists("com.tencent.qqnt.msg.KernelServiceUtil", cl);
            if (ksuClass != null) {
                for (Method m : ksuClass.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0) {
                        try {
                            m.setAccessible(true);
                            Object res = m.invoke(null);
                            if (res != null) {
                                // 检查是否有 setMsgEmojiLikes 方法
                                if (hasSetMsgEmojiLikes(res.getClass())) {
                                    registerMsgService(res);
                                    return res;
                                }
                                // 或者是 Session，检查 getMsgService()
                                try {
                                    Method getMsg = res.getClass().getMethod("getMsgService");
                                    Object sub = getMsg.invoke(res);
                                    if (sub != null && hasSetMsgEmojiLikes(sub.getClass())) {
                                        registerMsgService(sub);
                                        return sub;
                                    }
                                } catch (Throwable ignored) {
                                }
                                // 或者是 MsgService包装类，检查 getService()
                                try {
                                    Method getSvc = res.getClass().getMethod("getService");
                                    Object sub = getSvc.invoke(res);
                                    if (sub != null && hasSetMsgEmojiLikes(sub.getClass())) {
                                        registerMsgService(sub);
                                        return sub;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 通道 2：MobileQQ waitAppRuntime
        try {
            Class<?> mobileQQClass = XposedHelpers.findClassIfExists("mqq.app.MobileQQ", cl);
            if (mobileQQClass != null) {
                Object mobileQQ = XposedHelpers.getStaticObjectField(mobileQQClass, "sMobileQQ");
                if (mobileQQ != null) {
                    Object appRuntime = XposedHelpers.callMethod(mobileQQ, "waitAppRuntime", new Class<?>[]{Class.forName("mqq.app.AppActivity")}, (Object) null);
                    if (appRuntime == null) {
                        appRuntime = XposedHelpers.callMethod(mobileQQ, "peekAppRuntime");
                    }
                    if (appRuntime != null) {
                        Class<?> msgServiceApi = XposedHelpers.findClassIfExists("com.tencent.qqnt.msg.api.IMsgService", cl);
                        if (msgServiceApi != null) {
                            Object msgService = XposedHelpers.callMethod(appRuntime, "getRuntimeService", msgServiceApi, "");
                            if (msgService != null) {
                                try {
                                    Object kernel = XposedHelpers.callMethod(msgService, "getService");
                                    if (kernel != null) {
                                        registerMsgService(kernel);
                                        return kernel;
                                    }
                                } catch (Throwable ignored) {
                                }
                                registerMsgService(msgService);
                                return msgService;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static boolean hasSetMsgEmojiLikes(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().contains("setMsgEmojiLike")) return true;
        }
        return false;
    }

    private static Object createContact(ClassLoader cl, int chatType, String peerUid, String guildId) {
        String[] contactClassNames = {
                "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
                "com.tencent.qqnt.kernel.nativeinterface.Contact"
        };

        for (String cName : contactClassNames) {
            try {
                Class<?> contactClass = XposedHelpers.findClassIfExists(cName, cl);
                if (contactClass == null) continue;

                for (Constructor<?> c : contactClass.getDeclaredConstructors()) {
                    Class<?>[] pts = c.getParameterTypes();
                    if (pts.length == 3 && pts[0] == int.class && pts[1] == String.class && pts[2] == String.class) {
                        c.setAccessible(true);
                        return c.newInstance(chatType, peerUid, guildId);
                    }
                }

                Object obj = contactClass.newInstance();
                XposedHelpers.setObjectField(obj, "chatType", chatType);
                XposedHelpers.setObjectField(obj, "peerUid", peerUid);
                XposedHelpers.setObjectField(obj, "guildId", guildId);
                return obj;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findSetMsgEmojiLikesMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals("setMsgEmojiLikes") || m.getName().equals("setMsgEmojiLike")) {
                return m;
            }
        }
        return null;
    }

    private static Object createCallbackProxy(ClassLoader cl, Class<?>[] paramTypes, OnEmojiResultListener resultListener) {
        for (Class<?> type : paramTypes) {
            if (type.isInterface() && type.getName().contains("Callback")) {
                try {
                    return Proxy.newProxyInstance(cl, new Class<?>[]{type}, (proxy, method, args) -> {
                        AppLogger.i(TAG, "JNI 回调响应 -> " + method.getName() + ": " + Arrays.toString(args));
                        if (resultListener != null && args != null && args.length >= 1 && args[0] instanceof Number) {
                            int code = ((Number) args[0]).intValue();
                            String err = args.length >= 2 && args[1] != null ? args[1].toString() : "";
                            resultListener.onResult(code, err);
                        }
                        return null;
                    });
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object[] buildArgs(Class<?>[] paramTypes, Object contact, long msgSeq, String emojiIdStr, long emojiType, boolean isSet, Object callback) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> type = paramTypes[i];
            if (contact != null && type.isAssignableFrom(contact.getClass())) {
                args[i] = contact;
            } else if (type == long.class || type == Long.class) {
                args[i] = (i == 1) ? msgSeq : emojiType; // 第2个参数为 msgSeq，第4个为 emojiType (1L/2L)
            } else if (type == String.class) {
                args[i] = emojiIdStr;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = isSet;
            } else {
                args[i] = callback; // 回调对象
            }
        }
        return args;
    }

    private static long extractMsgSeq(Object msgRecord) {
        try {
            Object seq = XposedHelpers.getObjectField(msgRecord, "msgSeq");
            if (seq instanceof Number) return ((Number) seq).longValue();
        } catch (Throwable ignored) {
        }
        try {
            Object id = XposedHelpers.getObjectField(msgRecord, "msgId");
            if (id instanceof Number) return ((Number) id).longValue();
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static int extractIntField(Object obj, String fieldName, int def) {
        try {
            Object v = XposedHelpers.getObjectField(obj, fieldName);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static String extractStringField(Object obj, String fieldName, String def) {
        try {
            Object v = XposedHelpers.getObjectField(obj, fieldName);
            if (v != null) return v.toString();
        } catch (Throwable ignored) {
        }
        return def;
    }
}
