package com.emoji.reactor.hook;

import com.emoji.reactor.util.AppLogger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * QQ NT UIN (纯数字QQ号) 与 UID (u_xxxx加密字符串) 双向映射工具 (UidUinHelper)
 * 解决 QQ NT 底层全量走 UID 导致按 QQ号无法命中的核心死穴
 * 支持动态重试与启动主动预热机制
 */
public class UidUinHelper {

    private static final String TAG = "UidUinHelper";

    private static volatile Object sRelationApi = null;
    private static volatile Method sGetUidFromUin = null;
    private static volatile Method sGetUinFromUid = null;
    private static volatile ClassLoader sClassLoader = null;

    private static final Map<String, String> uinToUidMap = new ConcurrentHashMap<>();
    private static final Map<String, String> uidToUinMap = new ConcurrentHashMap<>();

    public static void init(ClassLoader cl) {
        if (cl != null) {
            sClassLoader = cl;
        }
        ensureApi();
    }

    private static synchronized boolean ensureApi() {
        if (sRelationApi != null) return true;
        if (sClassLoader == null) return false;

        try {
            Class<?> apiClass = XposedHelpers.findClassIfExists(
                    "com.tencent.relation.common.api.IRelationNTUinAndUidApi", sClassLoader);
            if (apiClass != null) {
                Class<?> qRouteClass = XposedHelpers.findClassIfExists(
                        "com.tencent.mobileqq.qroute.QRoute", sClassLoader);
                if (qRouteClass != null) {
                    Object impl = XposedHelpers.callStaticMethod(qRouteClass, "api", apiClass);
                    if (impl != null) {
                        sRelationApi = impl;
                        sGetUidFromUin = XposedHelpers.findMethodExactIfExists(apiClass, "getUidFromUin", String.class);
                        sGetUinFromUid = XposedHelpers.findMethodExactIfExists(apiClass, "getUinFromUid", String.class);
                        AppLogger.i(TAG, "已成功绑定 QQ 官方 IRelationNTUinAndUidApi 双向转换接口！");
                        // 主动预热所有已配置的 UIN -> UID 映射
                        warmUpConfiguredUins();
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "ensureApi 尝试中: " + t.getMessage());
        }
        return false;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sIsWarmingUp = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 主动将已配置的自定义头像 UIN 批量转换为 UID 并注入缓存，彻底消除冷启动与旧消息时延
     */
    public static void warmUpConfiguredUins() {
        if (sRelationApi == null || sGetUidFromUin == null) {
            ensureApi();
        }
        if (sRelationApi == null || sGetUidFromUin == null) return;
        if (!sIsWarmingUp.compareAndSet(false, true)) return;

        try {
            java.util.Set<String> uins = RemoteConfigHelper.getAllConfiguredUins();
            AppLogger.i(TAG, "warmUpConfiguredUins 启动，配置中的受控 UIN 集合: " + uins);
            for (String uin : uins) {
                if (uin != null && !uin.isEmpty()) {
                    String uid = getUidFromUin(uin);
                    if (uid != null && !uid.isEmpty()) {
                        RemoteConfigHelper.linkUidToUin(uid, uin);
                        AppLogger.i(TAG, "【主动预热完成】UIN=" + uin + " <=> UID=" + uid);
                    } else {
                        AppLogger.i(TAG, "【主动预热未查到对应UID】UIN=" + uin);
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "warmUpConfiguredUins 异常", t);
        } finally {
            sIsWarmingUp.set(false);
        }
    }

    /**
     * 动态双向绑定 UIN 与 UID（在消息、成员、会话加载时自动嗅探学习）
     */
    public static void bindUinAndUid(String uin, String uid) {
        if (uin == null || uid == null) return;
        uin = uin.trim();
        uid = uid.trim();
        if (uin.isEmpty() || uid.isEmpty() || "0".equals(uin)) return;

        uinToUidMap.put(uin, uid);
        uidToUinMap.put(uid, uin);
        RemoteConfigHelper.linkUidToUin(uid, uin);
    }

    public static String getUidFromUin(String uin) {
        if (uin == null || uin.trim().isEmpty()) return null;
        uin = uin.trim();
        String cached = uinToUidMap.get(uin);
        if (cached != null) return cached;

        ensureApi();

        if (sRelationApi != null && sGetUidFromUin != null) {
            try {
                Object res = sGetUidFromUin.invoke(sRelationApi, uin);
                AppLogger.d(TAG, "IRelationNTUinAndUidApi.getUidFromUin(" + uin + ") 返回: " + res);
                if (res instanceof String && !((String) res).isEmpty()) {
                    String uid = (String) res;
                    uinToUidMap.put(uin, uid);
                    uidToUinMap.put(uid, uin);
                    RemoteConfigHelper.linkUidToUin(uid, uin);
                    return uid;
                }
            } catch (Throwable t) {
                AppLogger.d(TAG, "getUidFromUin 调用失败: " + t.getMessage());
            }
        }
        return null;
    }

    public static String getUinFromUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) return null;
        uid = uid.trim();
        String cached = uidToUinMap.get(uid);
        if (cached != null) return cached;

        ensureApi();

        if (sRelationApi != null && sGetUinFromUid != null) {
            try {
                Object res = sGetUinFromUid.invoke(sRelationApi, uid);
                AppLogger.d(TAG, "IRelationNTUinAndUidApi.getUinFromUid(" + uid + ") 返回: " + res);
                if (res instanceof String && !((String) res).isEmpty()) {
                    String uin = (String) res;
                    uidToUinMap.put(uid, uin);
                    uinToUidMap.put(uin, uid);
                    RemoteConfigHelper.linkUidToUin(uid, uin);
                    return uin;
                }
            } catch (Throwable t) {
                AppLogger.d(TAG, "getUinFromUid 调用失败: " + t.getMessage());
            }
        }
        return null;
    }
}
