package com.emoji.reactor.feature.impl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.emoji.reactor.R;
import com.emoji.reactor.feature.BaseFeature;
import com.emoji.reactor.hook.MainHook;
import com.emoji.reactor.hook.RemoteConfigHelper;
import com.emoji.reactor.hook.UidUinHelper;
import com.emoji.reactor.util.AppLogger;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import androidx.recyclerview.widget.RecyclerView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 个人单向自定义头像核心功能特征 (CustomAvatarFeature)
 * 工业级立体全场景拦截体系（Oracle 深度 Code Review 终极重构版）：
 * 1. 聊天气泡层 (AIOAvatarContentComponent): 解构 Lazy 容器，挂载顶层绝对防篡改 Overlay，杜绝布局风暴
 * 2. 腾讯全生态底层网络图片重定向 (URLDrawable.getDrawable): 全局拦截 qlogo.cn 与 Qzone 网络请求，直通本地文件
 * 3. 官方原生 FaceDrawable 安全绘制 (FaceDrawable.draw): 零 ClassCastException，在原有实例上安全重画自定义位图
 * 4. QQ NT 通用头像控件层 (QQProAvatarView / VasAvatar): 泛型对象深度解包 (extractUinFromObject) + 容器 Overlay
 * 5. 个人资料卡层 (AvatarLayout / ProfileActivity): 精准限定受控 UIN，杜绝陌生人与相册串台
 * 6. NT 最近会话列表层 (RecentAvatarViewWrapper): 仅对真实头像容器生效，杜绝整行覆盖
 * 7. 解码器与数据服务层 (FaceDecoder / QQAvatarDataServiceImpl / IKernelAvatarService)
 */
public class CustomAvatarFeature extends BaseFeature {

    public static final String KEY = "feature_custom_avatar";
    private static final String TAG = "CustomAvatarFeature";
    public static final int ID_CUSTOM_AVATAR_TAG = 0x7E110041;
    public static final String TAG_OVERLAY_VIEW = "reactor_avatar_overlay";

    private static volatile CustomAvatarFeature sInstance = null;

    public CustomAvatarFeature() {
        sInstance = this;
    }

    public static CustomAvatarFeature getInstance() {
        if (sInstance == null) {
            sInstance = new CustomAvatarFeature();
        }
        return sInstance;
    }

    // 工业级 LRU 圆形位图内存池（固定上限 48 项）
    private static final LruCache<String, Bitmap> circleBitmapCache = new LruCache<>(48);

    // 记录每个 UIN 已解码头像文件的物理修改时间戳，文件变动即刻自动失效重载
    private static final Map<String, Long> avatarLastModifiedMap = new java.util.concurrent.ConcurrentHashMap<>();

    // FaceDrawable 实例与目标 UIN 的弱引用安全绑定表（防内存泄漏，防 ClassCastException）
    private static final Map<Object, String> faceDrawableUinMap = Collections.synchronizedMap(new WeakHashMap<>());

    // Qzone 图片赋值防递归死循环守卫
    private static final ThreadLocal<Boolean> sIsSettingQzoneImage = new ThreadLocal<>();

    // 全局防重入方法集合，杜绝多 ClassLoader 下核心 Hook 重复级联挂载
    private static final java.util.Set<Method> sHookedMethodSet = Collections.synchronizedSet(new java.util.HashSet<>());

    // QQ 进程核心 ClassLoader 句柄
    private static volatile ClassLoader sQQClassLoader = null;

    // 空间 OIDB 加密 Token 动态映射池 (k Token <=> UIN)
    private static final Map<String, String> sOidbTokenToUinMap = new java.util.concurrent.ConcurrentHashMap<>();

    public static void clearCircleCache() {
        circleBitmapCache.evictAll();
        faceDrawableUinMap.clear();
        avatarLastModifiedMap.clear();
        sOidbTokenToUinMap.clear();
        clearQqNativeCaches();
        clearQzoneNativeCaches();
        AppLogger.i(TAG, "已彻底清空 QQ 内部圆形头像内存缓存与映射");
    }

    private static void clearQzoneNativeCaches() {
        Context ctx = MainHook.getAppContext();
        if (ctx == null) return;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            try {
                File cacheDir = ctx.getCacheDir();
                if (cacheDir != null) {
                    File[] qzoneDirs = {
                            new File(cacheDir, "qzone"),
                            new File(cacheDir, "image_manager_disk_cache"),
                            new File(cacheDir, "qzone_avatar"),
                            new File(ctx.getFilesDir(), "qzone")
                    };
                    for (File qDir : qzoneDirs) {
                        if (qDir.exists() && qDir.isDirectory()) {
                            File[] files = qDir.listFiles();
                            if (files != null) {
                                for (File f : files) {
                                    try {
                                        if (f.isFile() && f.length() < 1000000) {
                                            f.delete();
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                }
                AppLogger.d(TAG, "【异步清空 Qzone 本地头像磁盘与动态缓存完成】");
            } catch (Throwable t) {
                AppLogger.d(TAG, "clearQzoneNativeCaches 安全跳过: " + t.getMessage());
            }
        });
    }

    private static void clearQqNativeCaches() {
        ClassLoader cl = sQQClassLoader != null ? sQQClassLoader : MainHook.getClassLoader();
        if (cl == null) return;

        // 1. URLDrawable 全局内存池清空
        try {
            Class<?> ud = XposedHelpers.findClassIfExists("com.tencent.image.URLDrawable", cl);
            if (ud != null) {
                XposedHelpers.callStaticMethod(ud, "clearMemoryCache");
                AppLogger.i(TAG, "【成功清空 QQ 原生 URLDrawable 内存池】");
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "clear URLDrawable 缓存跳过: " + e.getMessage());
        }

        // 2. FaceDecoder 全局缓存清空
        try {
            Class<?> fdClass = XposedHelpers.findClassIfExists("com.tencent.mobileqq.app.face.FaceDecoder", cl);
            if (fdClass != null) {
                for (Field f : fdClass.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val instanceof LruCache) {
                            ((LruCache<?, ?>) val).evictAll();
                            AppLogger.i(TAG, "【成功清空 FaceDecoder 静态 LruCache】" + f.getName());
                        } else if (val instanceof Map) {
                            ((Map<?, ?>) val).clear();
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "clear FaceDecoder 缓存跳过: " + e.getMessage());
        }

        // 3. QQAvatarDataServiceImpl 缓存清空
        try {
            Class<?> ads = XposedHelpers.findClassIfExists("com.tencent.mobileqq.avatar.api.impl.QQAvatarDataServiceImpl", cl);
            if (ads != null) {
                for (Field f : ads.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val instanceof LruCache) {
                            ((LruCache<?, ?>) val).evictAll();
                        } else if (val instanceof Map) {
                            ((Map<?, ?>) val).clear();
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "clear QQAvatarDataServiceImpl 缓存跳过: " + e.getMessage());
        }
    }

    private static void learnOidbToken(String url, String uin) {
        if (url == null || uin == null || !url.contains("b=oidb") || !url.contains("k=")) return;
        int kIdx = url.indexOf("k=");
        if (kIdx != -1) {
            String sub = url.substring(kIdx + 2);
            int end = sub.indexOf('&');
            String token = (end != -1) ? sub.substring(0, end) : sub;
            if (!token.isEmpty() && token.length() >= 6) {
                sOidbTokenToUinMap.put(token, uin);
                AppLogger.i(TAG, "【动态学习空间OIDB Token】Token=" + token + " <=> UIN=" + uin);
            }
        }
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public int getTitleRes() {
        return R.string.feature_custom_avatar_title;
    }

    @Override
    public int getDescRes() {
        return R.string.feature_custom_avatar_desc;
    }

    @Override
    public boolean isDefaultEnabled() {
        return true;
    }

    public static boolean isEnabledRuntime() {
        return RemoteConfigHelper.isModuleEnabled() && RemoteConfigHelper.isFeatureEnabled(KEY, true);
    }

    @Override
    public void onInit(ClassLoader cl) throws Throwable {
        sQQClassLoader = cl;
        UidUinHelper.init(cl);
        clearQqNativeCaches();
        clearQzoneNativeCaches();
        hookURLDrawable(cl);
        hookFaceDrawableSafe(cl);
        hookQQProAvatarView(cl);
        hookAioAvatarComponent(cl);
        hookRecentChats(cl);
        hookProfileCardComponents(cl);
        hookProfileActivity(cl);
        hookKernelAvatarService(cl);
        hookAvatarDataService(cl);
        hookFaceDecoder(cl);
        hookRecentContactInfo(cl);
        hookMemberInfo(cl);
        hookListAdapters(cl);
        hookQzone(cl);
        hookLibra(cl);
        hookStUser(cl);
    }

    public static void onDynamicClassLoaderLoaded(ClassLoader cl) {
        if (cl == null) return;
        try {
            CustomAvatarFeature feature = getInstance();
            feature.hookURLDrawable(cl);
            feature.hookLibra(cl);
            feature.hookQzone(cl);
            hookStUser(cl);
            feature.hookFaceDecoder(cl);
        } catch (Throwable t) {
            AppLogger.d(TAG, "onDynamicClassLoaderLoaded 安全跳过: " + t.getMessage());
        }
    }

    /**
     * 通用泛型对象解包工具：从各种复杂 NT 包装对象中安全萃取 UIN / UID
     */
    private static boolean isValidId(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 5) return false;
        if (s.startsWith("u_") && s.length() >= 8) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    public static String extractRawIdFromObject(Object obj) {
        return extractRawIdFromObject(obj, 0);
    }

    private static final Map<Class<?>, Field[]> sClassFieldsCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static Field[] getCachedDeclaredFields(Class<?> clazz) {
        if (clazz == null) return new Field[0];
        Field[] fields = sClassFieldsCache.get(clazz);
        if (fields == null) {
            try {
                fields = clazz.getDeclaredFields();
                for (Field f : fields) {
                    f.setAccessible(true);
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "getCachedDeclaredFields 安全跳过: " + e.getMessage());
                fields = new Field[0];
            }
            sClassFieldsCache.put(clazz, fields);
        }
        return fields;
    }

    private static String extractRawIdFromObject(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        if (depth > 0 && (obj instanceof View || obj instanceof Context || obj instanceof Drawable)) return null;

        if (obj instanceof String) {
            String str = ((String) obj).trim();
            if (isValidId(str)) return str;
            if (str.contains("_")) {
                String[] parts = str.split("_");
                for (String p : parts) {
                    p = p.trim();
                    if (isValidId(p)) return p;
                }
            }
            return null;
        }
        if (obj instanceof Number) {
            long val = ((Number) obj).longValue();
            if (val > 10000L && val < 4294967295L) {
                return String.valueOf(val);
            }
            return null;
        }

        String[] possibleFields = {
                "uin", "uid", "userUin", "peerUin", "peerUid", "senderUin", "senderUid",
                "memberUin", "troopMemberUin", "mUin", "curUin", "avatarUrl", "hostUin", "qzone_uin",
                "to_uin", "toUin", "cardUin", "targetUin", "key_uin",
                "contactInfo", "recentContactInfo", "info", "user", "userInfo", "cellUserInfo",
                "feedData", "commInfo", "userKey", "feedUser", "account", "friend", "member",
                "data", "mData", "item", "mItem", "feed", "mFeed", "businessFeedData", "mBusinessFeedData",
                "d", "a", "b"
        };
        for (String f : possibleFields) {
            try {
                Object val = XposedHelpers.getObjectField(obj, f);
                if (val != null && !(val instanceof View) && !(val instanceof Context) && !(val instanceof Drawable) && !(val instanceof android.content.res.Resources)) {
                    String extracted = extractRawIdFromObject(val, depth + 1);
                    if (extracted != null) {
                        tryLearnAvatarUrl(obj, extracted);
                        return extracted;
                    }
                }
            } catch (Exception e) {
                // 频繁尝试取字段属性，属于正常试探
            }
        }

        String[] possibleGetters = {
                "getUin", "getUid", "getPeerUin", "getSenderUin", "getMemberUin",
                "getContactInfo", "getRecentContactInfo", "getUserInfo", "getUser",
                "getCellUserInfo", "getFeedData", "getCommInfo", "getAuthor", "getAccount",
                "getData", "getItem", "getFeed"
        };
        for (String g : possibleGetters) {
            try {
                Object val = XposedHelpers.callMethod(obj, g);
                if (val != null && !(val instanceof View) && !(val instanceof Context) && !(val instanceof Drawable) && !(val instanceof android.content.res.Resources)) {
                    String extracted = extractRawIdFromObject(val, depth + 1);
                    if (extracted != null) {
                        tryLearnAvatarUrl(obj, extracted);
                        return extracted;
                    }
                }
            } catch (Exception e) {
                // 正常试探方法
            }
        }

        try {
            for (java.lang.reflect.Field field : getCachedDeclaredFields(obj.getClass())) {
                try {
                    Object val = field.get(obj);
                    if (val != null && !(val instanceof View) && !(val instanceof Context) && !(val instanceof Drawable) && !(val instanceof android.content.res.Resources)) {
                        String extracted = extractRawIdFromObject(val, depth + 1);
                        if (extracted != null) {
                            tryLearnAvatarUrl(obj, extracted);
                            return extracted;
                        }
                    }
                } catch (Exception e) {
                    // 字段读取试探
                }
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "extractRawIdFromObject 深度反射安全跳过: " + e.getMessage());
        }

        return null;
    }

    private static void tryLearnAvatarUrl(Object obj, String uin) {
        if (obj == null || uin == null) return;
        String[] urlFields = {"avatarUrl", "logo", "iconUrl", "url", "headUrl"};
        for (String f : urlFields) {
            try {
                Object u = XposedHelpers.getObjectField(obj, f);
                if (u instanceof String) {
                    learnOidbToken((String) u, uin);
                }
            } catch (Exception e) {
                // 试探URL字段
            }
        }
    }

    private static String extractUinFromObject(Object obj) {
        String rawId = extractRawIdFromObject(obj);
        if (rawId != null && RemoteConfigHelper.getCustomAvatarPath(rawId) != null) {
            return rawId;
        }
        return null;
    }

    private static String getUinFromIntent(Intent it) {
        if (it == null) return null;
        String[] intentKeys = {
                "uin", "to_uin", "key_uin", "targetUin", "cardUin", "toUin",
                "qzone_uin", "hostUin", "host_uin", "user_uin", "target_uin", "current_uin"
        };
        for (String k : intentKeys) {
            try {
                String u = it.getStringExtra(k);
                if (u != null && isValidId(u)) {
                    return u.trim();
                }
            } catch (Exception e) {
                // 试探Intent键
            }
            try {
                long u = it.getLongExtra(k, 0L);
                if (u > 10000L) {
                    return String.valueOf(u);
                }
            } catch (Exception e) {
                // 试探Intent数值
            }
        }
        try {
            Object aio = it.getParcelableExtra("AllInOne");
            if (aio != null) {
                String u = extractRawIdFromObject(aio);
                if (u != null) return u;
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "AllInOne读取安全跳过: " + e.getMessage());
        }
        if (it.getExtras() != null) {
            for (String k : it.getExtras().keySet()) {
                Object val = it.getExtras().get(k);
                String extracted = extractRawIdFromObject(val);
                if (extracted != null) return extracted;
            }
        }
        return null;
    }

    public static Activity getActivityFromContext(Context context) {
        Context cur = context;
        while (cur instanceof android.content.ContextWrapper) {
            if (cur instanceof Activity) {
                return (Activity) cur;
            }
            cur = ((android.content.ContextWrapper) cur).getBaseContext();
        }
        return null;
    }

    /**
     * 核心辅助：为头像宿主容器挂载防篡改覆盖层（彻底消除 OnLayoutChangeListener，杜绝无限布局死循环与卡顿）
     */
    private static void applyOverlayToContainer(ViewGroup vg, String targetId, Bitmap circleBm) {
        if (vg == null) return;

        if (targetId == null || circleBm == null) {
            // 未命中受控目标或一键复原，彻底清除状态Tag并从父容器物理移除覆盖层
            vg.setTag(ID_CUSTOM_AVATAR_TAG, null);
            View overlay = vg.findViewWithTag(TAG_OVERLAY_VIEW);
            if (overlay != null) {
                overlay.setVisibility(View.GONE);
                try {
                    vg.removeView(overlay);
                } catch (Exception e) {
                    AppLogger.d(TAG, "removeView 安全跳过: " + e.getMessage());
                }
            }
            return;
        }

        vg.setTag(ID_CUSTOM_AVATAR_TAG, targetId);

        // 挂载顶层绝对防篡改 Overlay ImageView
        ImageView overlay = (ImageView) vg.findViewWithTag(TAG_OVERLAY_VIEW);
        if (overlay == null) {
            overlay = new ImageView(vg.getContext());
            overlay.setTag(TAG_OVERLAY_VIEW);
            overlay.setScaleType(ImageView.ScaleType.FIT_XY);
            overlay.setClickable(false); // 点击事件透传给底层宿主
            ViewGroup.LayoutParams lp = null;
            try {
                Object generated = XposedHelpers.callMethod(vg, "generateLayoutParams", new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                if (generated instanceof ViewGroup.LayoutParams) {
                    lp = (ViewGroup.LayoutParams) generated;
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "generateLayoutParams 安全跳过: " + e.getMessage());
            }
            if (lp == null) {
                lp = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
            }
            vg.addView(overlay, lp);
        }
        overlay.setImageBitmap(circleBm);
        overlay.setVisibility(View.VISIBLE);
        int childCount = vg.getChildCount();
        if (childCount > 0 && vg.getChildAt(childCount - 1) != overlay) {
            overlay.bringToFront();
        }
    }

    private static View unpackView(Object obj) {
        if (obj == null) return null;
        if (obj instanceof View) return (View) obj;
        try {
            if (obj.getClass().getName().contains("Lazy")) {
                Object val = XposedHelpers.callMethod(obj, "getValue");
                if (val instanceof View) return (View) val;
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "unpackView 安全跳过: " + e.getMessage());
        }
        return null;
    }

    private static List<View> findAllViews(View root) {
        List<View> list = new ArrayList<>();
        if (root == null) return list;
        list.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                list.addAll(findAllViews(child));
            }
        }
        return list;
    }

    private static List<ImageView> findAllImageViews(View root) {
        List<ImageView> list = new ArrayList<>();
        if (root == null) return list;
        if (root instanceof ImageView) {
            list.add((ImageView) root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                list.addAll(findAllImageViews(child));
            }
        }
        return list;
    }

    /**
     * 终极核武器防线 1：URLDrawable 全局底层网络请求拦截与重定向（攻克 Qzone 空间头像、动态流及全场景网络头像）
     */
    private void hookURLDrawable(ClassLoader cl) {
        try {
            Class<?> urlDrawableClass = XposedHelpers.findClassIfExists("com.tencent.image.URLDrawable", cl);
            if (urlDrawableClass == null) return;

            XC_MethodHook urlHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isEnabledRuntime()) return;
                    if (param.args == null || param.args.length == 0) return;

                    Object arg0 = param.args[0];
                    String urlStr = null;
                    if (arg0 instanceof String) {
                        urlStr = (String) arg0;
                    } else if (arg0 instanceof URL) {
                        urlStr = ((URL) arg0).toString();
                    }
                    if (urlStr == null || urlStr.isEmpty()) return;

                    // 头像特征前置过滤：必须包含官方头像特征域名或路径，严禁拦截聊天普通图片
                    boolean isAvatarUrl = urlStr.contains("qlogo") || urlStr.contains("avatar") 
                            || urlStr.contains("head") || urlStr.contains("face");
                    boolean isChatPic = urlStr.contains("gchatpic") || urlStr.contains("c2cpic") 
                            || urlStr.contains("chatthumb") || urlStr.contains("offpic");
                    if (!isAvatarUrl || isChatPic) return;

                    // 检查 URL 是否包含受控账号 (UIN 或 UID) 或标准头像域名特征
                    for (String uin : RemoteConfigHelper.getAllConfiguredUins()) {
                        String uid = UidUinHelper.getUidFromUin(uin);
                        boolean matches = urlStr.contains(uin) || (uid != null && !uid.isEmpty() && urlStr.contains(uid));
                        if (!matches && !sOidbTokenToUinMap.isEmpty()) {
                            for (Map.Entry<String, String> tokenEntry : sOidbTokenToUinMap.entrySet()) {
                                if (uin.equals(tokenEntry.getValue()) && urlStr.contains(tokenEntry.getKey())) {
                                    matches = true;
                                    break;
                                }
                            }
                        }
                        if (matches) {
                            String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                            if (customPath != null) {
                                File f = new File(customPath);
                                if (f.exists() && f.length() > 0) {
                                    // 协议级重定向为纯净本地文件 URI (严禁携带 ?t= 参数，防底层解析报 FileNotFoundException)
                                    String localUri = "file://" + customPath;
                                    if (arg0 instanceof String) {
                                        param.args[0] = localUri;
                                    } else {
                                        param.args[0] = new URL("file", "", customPath);
                                    }
                                    AppLogger.i(TAG, "【URLDrawable重定向成功】UIN=" + uin + " -> " + localUri);
                                    return;
                                }
                            }
                        }
                    }
                }
            };

            int count = 0;
            for (Method m : urlDrawableClass.getDeclaredMethods()) {
                if ("getDrawable".equals(m.getName()) && m.getParameterTypes().length >= 1) {
                    Class<?> firstParam = m.getParameterTypes()[0];
                    if ((firstParam == String.class || firstParam == URL.class) && sHookedMethodSet.add(m)) {
                        XposedBridge.hookMethod(m, urlHook);
                        count++;
                    }
                }
            }
            AppLogger.i(TAG, "已成功挂载 URLDrawable 全局网络头像重定向探针 (方法数: " + count + ")！");
        } catch (Throwable t) {
            AppLogger.d(TAG, "hookURLDrawable 异常: " + t.getMessage());
        }
    }

    /**
     * 终极防线 2：FaceDrawable 安全绘制拦截 (零 ClassCastException，直接在其 draw 方法上重画)
     */
    private void hookFaceDrawableSafe(ClassLoader cl) {
        try {
            Class<?> faceDrawableClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.app.face.FaceDrawable", cl);
            if (faceDrawableClass == null) return;

            // 1. 工厂方法嗅探与绑定：只记录实例与 UIN 的对应关系，绝不篡改返回类型！
            XC_MethodHook factoryHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isEnabledRuntime()) return;
                    Object result = param.getResult();
                    if (result == null || param.args == null || param.args.length == 0) return;

                    for (Object arg : param.args) {
                        String uin = extractUinFromObject(arg);
                        if (uin != null) {
                            faceDrawableUinMap.put(result, uin);
                            return;
                        }
                    }
                }
            };

            for (Method m : faceDrawableClass.getDeclaredMethods()) {
                if ((m.getName().contains("Face") || m.getName().contains("Avatar"))
                        && Drawable.class.isAssignableFrom(m.getReturnType())) {
                    XposedBridge.hookMethod(m, factoryHook);
                }
            }

            // 2. 绘制层拦截：在原生的 draw(Canvas) 触发时，直接重绘自定义位图并短路跳过原图绘制！
            Method drawMethod = XposedHelpers.findMethodExactIfExists(faceDrawableClass, "draw", Canvas.class);
            if (drawMethod != null) {
                XposedBridge.hookMethod(drawMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        Object instance = param.thisObject;
                        String uin = faceDrawableUinMap.get(instance);
                        if (uin == null) return;

                        String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                        if (customPath == null) return;

                        Bitmap circleBm = getOrCreateCircleBitmap(uin, customPath);
                        if (circleBm == null) return;

                        Canvas canvas = (Canvas) param.args[0];
                        if (canvas != null && instance instanceof Drawable) {
                            Rect bounds = ((Drawable) instance).getBounds();
                            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                                canvas.drawBitmap(circleBm, null, bounds, null);
                                param.setResult(null); // 安全截断原生绘制，零异常风险！
                            }
                        }
                    }
                });
            }
            AppLogger.i(TAG, "已成功挂载 FaceDrawable 安全绘制与绑定探针！");
        } catch (Throwable t) {
            AppLogger.d(TAG, "hookFaceDrawableSafe 异常: " + t.getMessage());
        }
    }

    /**
     * 终极防线 3：QQProAvatarView 全量泛型解包与 Overlay 挂载（攻克好友列表、群成员、会话列表）
     */
    private void hookQQProAvatarView(ClassLoader cl) {
        String[] viewClasses = {
                "com.tencent.mobileqq.proavatar.QQProAvatarView",
                "com.tencent.mobileqq.proavatar.QQProAvatarLayerImageView",
                "com.tencent.mobileqq.vas.avatar.VasAvatar",
                "com.tencent.mobileqq.avatar.dynamicavatar.DynamicAvatarView"
        };

        for (String clsName : viewClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(clsName, cl);
                if (clazz == null) continue;

                XC_MethodHook viewMethodHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        Object target = param.thisObject;
                        if (!(target instanceof View)) return;
                        View v = (View) target;

                        String rawId = null;
                        if (param.args != null) {
                            for (Object arg : param.args) {
                                rawId = extractRawIdFromObject(arg);
                                if (rawId != null) break;
                            }
                        }
                        if (rawId == null) {
                            rawId = extractRawIdFromObject(target);
                        }

                        if (rawId != null) {
                            String customPath = RemoteConfigHelper.getCustomAvatarPath(rawId);
                            if (customPath != null) {
                                Bitmap circleBm = getOrCreateCircleBitmap(rawId, customPath);
                                if (circleBm != null) {
                                    if (v instanceof ViewGroup) {
                                        applyOverlayToContainer((ViewGroup) v, rawId, circleBm);
                                        AppLogger.i(TAG, "【QQProAvatarView容器覆盖成功】ID=" + rawId);
                                    } else if (v instanceof ImageView) {
                                        v.setTag(ID_CUSTOM_AVATAR_TAG, rawId);
                                        ((ImageView) v).setImageBitmap(circleBm);
                                        AppLogger.i(TAG, "【QQProAvatarView ImageView赋值成功】ID=" + rawId);
                                    }
                                    return;
                                }
                            } else {
                                // 视图复用清理：该视图被复用给普通未配置好友，彻底隐藏清除覆盖层！
                                if (v instanceof ViewGroup) {
                                    applyOverlayToContainer((ViewGroup) v, null, null);
                                } else if (v instanceof ImageView) {
                                    v.setTag(ID_CUSTOM_AVATAR_TAG, null);
                                }
                            }
                        }
                    }
                };

                if ("com.tencent.mobileqq.proavatar.QQProAvatarView".equals(clsName)) {
                    for (Method m : clazz.getDeclaredMethods()) {
                        AppLogger.d(TAG, "QQProAvatarView方法: " + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
                    }
                }

                XC_MethodHook canvasDrawHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        View v = (View) param.thisObject;
                        Object tag = v.getTag(ID_CUSTOM_AVATAR_TAG);
                        if (tag instanceof String) {
                            String targetId = (String) tag;
                            String customPath = RemoteConfigHelper.getCustomAvatarPath(targetId);
                            if (customPath != null) {
                                Bitmap bm = getOrCreateCircleBitmap(targetId, customPath);
                                if (bm != null && param.args != null && param.args.length > 0 && param.args[0] instanceof Canvas) {
                                    Canvas canvas = (Canvas) param.args[0];
                                    int w = v.getWidth();
                                    int h = v.getHeight();
                                    if (w > 0 && h > 0) {
                                        canvas.drawBitmap(bm, null, new Rect(0, 0, w, h), null);
                                    }
                                }
                            }
                        }
                    }
                };

                for (Method m : clazz.getDeclaredMethods()) {
                    String mName = m.getName();
                    if ("dispatchDraw".equals(mName)) {
                        if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == Canvas.class) {
                            XposedBridge.hookMethod(m, canvasDrawHook);
                        }
                        continue;
                    }
                    if (mName.equals("onMeasure") || mName.equals("onLayout") || mName.equals("onDraw")
                            || mName.equals("draw") || mName.equals("layout")
                            || mName.equals("onTouchEvent") || mName.equals("dispatchTouchEvent") || mName.equals("onSizeChanged")
                            || mName.equals("onWindowVisibilityChanged") || mName.equals("onAttachedToWindow") || mName.equals("onDetachedFromWindow")) {
                        continue;
                    }
                    boolean isTargetMethod = "y".equals(mName) || "z".equals(mName)
                            || mName.contains("load") || mName.contains("bind")
                            || mName.contains("update") || mName.contains("setAvatar")
                            || mName.contains("setFace");
                    if (isTargetMethod && m.getParameterTypes().length > 0) {
                        XposedBridge.hookMethod(m, viewMethodHook);
                    }
                }
                AppLogger.i(TAG, "已成功挂载 " + clsName + " 全量泛型解包与画布绘制探针！");
            } catch (Throwable t) {
                AppLogger.d(TAG, "hookQQProAvatarView 降级: " + clsName + ", " + t.getMessage());
            }
        }
    }

    /**
     * 防线 4：聊天气泡组件全方位深度接管 (AIOAvatarContentComponent)
     */
    private void hookAioAvatarComponent(ClassLoader cl) {
        try {
            Class<?> avatarCompClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent", cl);
            if (avatarCompClass == null) return;

            XC_MethodHook bindHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isEnabledRuntime()) return;
                    Object comp = param.thisObject;
                    if (comp == null) return;

                    String uinStr = null;
                    String uidStr = null;

                    try {
                        Object d = XposedHelpers.getObjectField(comp, "D");
                        if (d instanceof String && !((String) d).isEmpty()) uinStr = (String) d;
                    } catch (Exception e) {
                        // 试探字段D
                    }
                    try {
                        Object e = XposedHelpers.getObjectField(comp, "E");
                        if (e instanceof String && !((String) e).isEmpty()) uidStr = (String) e;
                    } catch (Exception ex) {
                        // 试探字段E
                    }

                    if (uinStr == null && param.args != null) {
                        for (Object arg : param.args) {
                            uinStr = extractUinFromObject(arg);
                            if (uinStr != null) break;
                        }
                    }

                    if (uinStr != null && uidStr != null) {
                        UidUinHelper.bindUinAndUid(uinStr, uidStr);
                    }

                    String targetId = (uinStr != null) ? uinStr : uidStr;
                    if (targetId == null) return;

                    String customPath = RemoteConfigHelper.getCustomAvatarPath(targetId);

                    View rootContainer = null;
                    try {
                        rootContainer = unpackView(XposedHelpers.callMethod(comp, "B1"));
                    } catch (Exception e) {
                        // 试探B1
                    }
                    if (rootContainer == null) {
                        try {
                            rootContainer = unpackView(XposedHelpers.getObjectField(comp, "h"));
                        } catch (Exception e) {
                            // 试探h
                        }
                    }
                    if (rootContainer == null) {
                        try {
                            rootContainer = unpackView(XposedHelpers.getObjectField(comp, "i"));
                        } catch (Exception e) {
                            // 试探i
                        }
                    }

                    if (rootContainer != null) {
                        if (customPath != null) {
                            Bitmap circleBm = getOrCreateCircleBitmap(targetId, customPath);
                            if (circleBm != null) {
                                if (rootContainer instanceof ViewGroup) {
                                    applyOverlayToContainer((ViewGroup) rootContainer, targetId, circleBm);
                                } else if (rootContainer instanceof ImageView) {
                                    rootContainer.setTag(ID_CUSTOM_AVATAR_TAG, targetId);
                                    ((ImageView) rootContainer).setImageBitmap(circleBm);
                                }
                                AppLogger.i(TAG, "【成功替换气泡头像】ID=" + targetId + " -> " + customPath);
                            }
                        } else {
                            if (rootContainer instanceof ViewGroup) {
                                applyOverlayToContainer((ViewGroup) rootContainer, null, null);
                            }
                        }
                    }
                }
            };

            for (Method m : avatarCompClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("A1".equals(name) || "R0".equals(name) || "V0".equals(name) || "handleUIState".equals(name)
                        || "y2".equals(name) || "h2".equals(name)) {
                    XposedBridge.hookMethod(m, bindHook);
                }
            }
            AppLogger.i(TAG, "已成功挂载 AIOAvatarContentComponent 气泡头像探针！");
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookAioAvatarComponent 异常", t);
        }
    }

    /**
     * 防线 5：NT 最近会话列表专属头像组件拦截（绝不覆盖整行 itemView，仅对真实头像容器生效）
     */
    private void hookRecentChats(ClassLoader cl) {
        String[] viewContainers = {
                "com.tencent.qqnt.chats.view.widget.RecentAvatarViewWrapper",
                "com.tencent.widget.RecentDynamicAvatarView",
                "com.tencent.qqnt.chats.view.widget.DefaultRecentAvatarView"
        };

        XC_MethodHook recentCanvasDrawHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isEnabledRuntime()) return;
                View v = (View) param.thisObject;
                Object tag = v.getTag(ID_CUSTOM_AVATAR_TAG);
                if (tag instanceof String) {
                    String targetId = (String) tag;
                    String customPath = RemoteConfigHelper.getCustomAvatarPath(targetId);
                    if (customPath != null) {
                        Bitmap bm = getOrCreateCircleBitmap(targetId, customPath);
                        if (bm != null && param.args != null && param.args.length > 0 && param.args[0] instanceof Canvas) {
                            Canvas canvas = (Canvas) param.args[0];
                            int w = v.getWidth();
                            int h = v.getHeight();
                            if (w > 0 && h > 0) {
                                canvas.drawBitmap(bm, null, new Rect(0, 0, w, h), null);
                            }
                        }
                    }
                }
            }
        };

        for (String clsName : viewContainers) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(clsName, cl);
                if (clazz == null) continue;

                for (Method m : clazz.getDeclaredMethods()) {
                    String mName = m.getName();
                    if (mName.equals("dispatchDraw") || mName.equals("onDraw") || mName.equals("draw")) {
                        if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == Canvas.class) {
                            XposedBridge.hookMethod(m, recentCanvasDrawHook);
                        }
                        continue;
                    }
                    if (mName.equals("onMeasure") || mName.equals("onLayout") || mName.equals("layout")
                            || mName.equals("onTouchEvent") || mName.equals("dispatchTouchEvent") || mName.equals("onSizeChanged")) {
                        continue;
                    }
                    boolean isTargetMethod = "y".equals(mName) || "z".equals(mName)
                            || mName.contains("load") || mName.contains("bind")
                            || mName.contains("update") || mName.contains("setAvatar")
                            || mName.contains("setFace");
                    if (isTargetMethod && m.getParameterTypes().length > 0) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                Object target = param.thisObject;
                                String rawId = null;
                                if (param.args != null) {
                                    for (Object arg : param.args) {
                                        rawId = extractRawIdFromObject(arg);
                                        if (rawId != null) break;
                                    }
                                }
                                if (rawId == null) {
                                    rawId = extractRawIdFromObject(target);
                                }
                                if (rawId != null) {
                                    String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                    if (custom != null) {
                                        Bitmap circleBm = getOrCreateCircleBitmap(rawId, custom);
                                        if (circleBm != null) {
                                            if (target instanceof ViewGroup) {
                                                applyOverlayToContainer((ViewGroup) target, rawId, circleBm);
                                                AppLogger.d(TAG, "【会话列表头像容器精准拦截成功】ID=" + rawId);
                                            } else if (target instanceof ImageView) {
                                                ((ImageView) target).setTag(ID_CUSTOM_AVATAR_TAG, rawId);
                                                ((ImageView) target).setImageBitmap(circleBm);
                                                AppLogger.d(TAG, "【会话列表ImageView精准拦截成功】ID=" + rawId);
                                            }
                                            return;
                                        }
                                    } else {
                                        if (target instanceof ViewGroup) {
                                            applyOverlayToContainer((ViewGroup) target, null, null);
                                        } else if (target instanceof ImageView) {
                                            ((ImageView) target).setTag(ID_CUSTOM_AVATAR_TAG, null);
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
                AppLogger.i(TAG, "已成功挂载 " + clsName + " 会话头像组件探针！");
            } catch (Throwable t) {
                AppLogger.d(TAG, "hookRecentChats 降级: " + clsName + ", " + t.getMessage());
            }
        }

        // 挂载 ChatsListAdapter.onBindViewHolder
        try {
            Class<?> adapterClass = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.chats.core.adapter.ChatsListAdapter", cl);
            if (adapterClass != null) {
                for (Method m : adapterClass.getDeclaredMethods()) {
                    if ("onBindViewHolder".equals(m.getName()) && m.getParameterTypes().length >= 2) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                Object holder = param.args[0];
                                int pos = (Integer) param.args[1];
                                Object adapter = param.thisObject;
                                String rawId = null;
                                try {
                                    Object item = XposedHelpers.callMethod(adapter, "getItem", pos);
                                    rawId = extractRawIdFromObject(item);
                                } catch (Exception e) {
                                    // 试探getItem
                                }
                                if (rawId == null) {
                                    rawId = extractRawIdFromObject(holder);
                                }
                                View row = null;
                                try {
                                    row = (View) XposedHelpers.getObjectField(holder, "itemView");
                                } catch (Exception e) {
                                    // 试探itemView
                                }
                                if (row instanceof ViewGroup) {
                                    if (rawId != null) {
                                        String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                        if (custom != null) {
                                            Bitmap bm = getOrCreateCircleBitmap(rawId, custom);
                                            if (bm != null) {
                                                for (View container : findAllAvatarContainers((ViewGroup) row)) {
                                                    if (container instanceof ViewGroup) {
                                                        applyOverlayToContainer((ViewGroup) container, rawId, bm);
                                                    }
                                                }
                                                AppLogger.i(TAG, "【ChatsListAdapter.onBindViewHolder 头像绑定成功】ID=" + rawId);
                                            }
                                        } else {
                                            for (View container : findAllAvatarContainers((ViewGroup) row)) {
                                                if (container instanceof ViewGroup) {
                                                    applyOverlayToContainer((ViewGroup) container, null, null);
                                                }
                                            }
                                        }
                                    } else {
                                        // 视图复用清理：若当前项无关联 ID，清除可能遗留的旧覆盖层
                                        for (View container : findAllAvatarContainers((ViewGroup) row)) {
                                            if (container instanceof ViewGroup) {
                                                applyOverlayToContainer((ViewGroup) container, null, null);
                                            }
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
                AppLogger.i(TAG, "已成功挂载 ChatsListAdapter 探针！");
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "ChatsListAdapter 挂载跳过: " + t.getMessage());
        }

        // 挂载 CommonRecentItemBuilder
        try {
            Class<?> builderClass = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.chats.core.adapter.builder.CommonRecentItemBuilder", cl);
            if (builderClass != null) {
                for (Method m : builderClass.getDeclaredMethods()) {
                    if (m.getParameterTypes().length >= 2) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                String rawId = null;
                                for (Object arg : param.args) {
                                    rawId = extractRawIdFromObject(arg);
                                    if (rawId != null) break;
                                }
                                if (rawId != null) {
                                    Object result = param.getResult();
                                    View row = null;
                                    if (result instanceof View) {
                                        row = (View) result;
                                    } else {
                                        for (Object arg : param.args) {
                                            if (arg instanceof View) {
                                                row = (View) arg;
                                                break;
                                            }
                                        }
                                    }
                                    if (row instanceof ViewGroup) {
                                        String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                        if (custom != null) {
                                            Bitmap bm = getOrCreateCircleBitmap(rawId, custom);
                                            if (bm != null) {
                                                for (View container : findAllAvatarContainers((ViewGroup) row)) {
                                                    if (container instanceof ViewGroup) {
                                                        applyOverlayToContainer((ViewGroup) container, rawId, bm);
                                                    }
                                                }
                                            }
                                        } else {
                                            for (View container : findAllAvatarContainers((ViewGroup) row)) {
                                                if (container instanceof ViewGroup) {
                                                    applyOverlayToContainer((ViewGroup) container, null, null);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
                AppLogger.i(TAG, "已成功挂载 CommonRecentItemBuilder 探针！");
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "CommonRecentItemBuilder 挂载跳过: " + t.getMessage());
        }
    }

    private static List<View> findAllAvatarContainers(ViewGroup root) {
        List<View> list = new ArrayList<>();
        if (root == null) return list;
        String cls = root.getClass().getName();
        if (cls.contains("RecentAvatar") || cls.contains("AvatarLayout") || cls.contains("ProAvatar")
                || cls.contains("DynamicAvatar") || cls.contains("RecentDynamic")) {
            list.add(root);
            return list;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c instanceof ViewGroup) {
                list.addAll(findAllAvatarContainers((ViewGroup) c));
            }
        }
        return list;
    }

    /**
     * 防线 6：个人主页资料卡专属组件 (严格限定受控 UIN，彻底杜绝陌生人与照片墙串台)
     */
    private void hookProfileCardComponents(ClassLoader cl) {
        String[] targetClasses = {
                "com.tencent.mobileqq.vas.avatar.AvatarLayout",
                "com.tencent.mobileqq.vas.avatar.api.impl.FriendProfileAvatarRedDotApiImpl",
                "com.tencent.mobileqq.profilecard.base.view.ProfileHeaderView",
                "com.tencent.mobileqq.profilecard.template.VasProfileTemplateAvatarView",
                "com.tencent.mobileqq.profilecard.vas.component.VasProfileAvatarComponent",
                "com.tencent.mobileqq.profilecard.vas.component.header.AbsVasProfileHeaderComponent",
                "com.tencent.mobileqq.profilecard.vas.component.header.VasProfileHeaderComponent"
        };

        for (String clsName : targetClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(clsName, cl);
                if (clazz == null) continue;

                XC_MethodHook hook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        Object target = param.thisObject;
                        if (target == null) return;

                        String uin = null;
                        if (param.args != null) {
                            for (Object arg : param.args) {
                                uin = extractUinFromObject(arg);
                                if (uin != null) break;
                            }
                        }

                        // 如果对象本身是 AvatarLayout，从其 Context 安全抽取当前页面的 UIN
                        if (uin == null && target instanceof ViewGroup && target.getClass().getName().contains("AvatarLayout")) {
                            Activity act = getActivityFromContext(((ViewGroup) target).getContext());
                            if (act != null) {
                                uin = getUinFromIntent(act.getIntent());
                            }
                        }

                        // 关键安全门禁：绝不无脑循环受控列表！未匹配到受控 UIN 直接退出！
                        if (uin == null) return;
                        String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                        if (customPath == null) return;

                        Bitmap circleBm = getOrCreateCircleBitmap(uin, customPath);
                        if (circleBm == null) return;

                        ViewGroup vgTarget = null;
                        if (target instanceof ViewGroup) {
                            vgTarget = (ViewGroup) target;
                        } else if (param.args != null) {
                            for (Object arg : param.args) {
                                if (arg instanceof ViewGroup) {
                                    vgTarget = (ViewGroup) arg;
                                    break;
                                }
                            }
                        }

                        if (vgTarget != null) {
                            applyOverlayToContainer(vgTarget, uin, circleBm);
                            AppLogger.i(TAG, "【资料卡专属容器覆盖成功】UIN=" + uin);
                        } else if (target instanceof ImageView) {
                            ((ImageView) target).setTag(ID_CUSTOM_AVATAR_TAG, uin);
                            ((ImageView) target).setImageBitmap(circleBm);
                            AppLogger.i(TAG, "【资料卡ImageView设置成功】UIN=" + uin);
                        }
                    }
                };

                for (Method m : clazz.getDeclaredMethods()) {
                    String mName = m.getName();
                    if (mName.contains("update") || mName.contains("init") || mName.contains("bind")
                            || mName.contains("setAvatar") || mName.contains("RedDot")) {
                        XposedBridge.hookMethod(m, hook);
                    }
                }
                try {
                    XposedHelpers.findAndHookMethod(clazz, "onAttachedToWindow", hook);
                } catch (Exception e) {
                    AppLogger.d(TAG, "hook onAttachedToWindow 跳过: " + e.getMessage());
                }
                try {
                    XposedHelpers.findAndHookMethod(clazz, "onLayout", boolean.class, int.class, int.class, int.class, int.class, hook);
                } catch (Exception e) {
                    AppLogger.d(TAG, "hook onLayout 跳过: " + e.getMessage());
                }
                AppLogger.i(TAG, "已成功挂载 " + clsName + " 资料卡探针！");
            } catch (Throwable t) {
                AppLogger.d(TAG, "hookProfileCardComponents 降级: " + clsName + ", " + t.getMessage());
            }
        }
    }

    private static WeakReference<Activity> sCurrentResumedActivity = null;

    public static void refreshVisibleAvatarsOnForeground() {
        Activity act = sCurrentResumedActivity != null ? sCurrentResumedActivity.get() : null;
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    View decor = act.getWindow().getDecorView();
                    if (decor instanceof ViewGroup) {
                        ViewGroup root = (ViewGroup) decor;
                        refreshAvatarsRecursively(root);

                        String actName = act.getClass().getName();
                        if (actName.contains("Profile") || actName.contains("Card")) {
                            String uin = getUinFromIntent(act.getIntent());
                            if (uin != null) {
                                String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                                Bitmap bm = customPath != null ? getOrCreateCircleBitmap(uin, customPath) : null;
                                findAndApplyAvatarLayoutOnly(root, uin, bm);
                            }
                        }
                        // 关键：触发消息主页列表及全部列表控件刷新！
                        refreshAllListAdapters(root);
                    }
                } catch (Throwable t) {
                    AppLogger.e(TAG, "refreshVisibleAvatarsOnForeground 异常", t);
                }
            }
        });
    }

    private static void refreshAllListAdapters(ViewGroup root) {
        if (root == null) return;
        for (View v : findAllViews(root)) {
            String clsName = v.getClass().getName();
            if (clsName.contains("RecyclerView") || v instanceof androidx.recyclerview.widget.RecyclerView) {
                try {
                    Object adapter = XposedHelpers.callMethod(v, "getAdapter");
                    if (adapter != null) {
                        boolean computing = false;
                        try {
                            computing = (Boolean) XposedHelpers.callMethod(v, "isComputingLayout");
                        } catch (Exception e) {
                            // 检查isComputingLayout
                        }
                        if (computing) {
                            v.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        Object a = XposedHelpers.callMethod(v, "getAdapter");
                                        if (a != null) XposedHelpers.callMethod(a, "notifyDataSetChanged");
                                    } catch (Exception e) {
                                        // 刷新异常
                                    }
                                }
                            });
                        } else {
                            XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
                        }
                        AppLogger.i(TAG, "【成功触发前台 RecyclerView 刷新】" + adapter.getClass().getName());
                    }
                } catch (Exception e) {
                    AppLogger.d(TAG, "RecyclerView notifyDataSetChanged 安全跳过: " + e.getMessage());
                }
            } else if (v instanceof android.widget.AbsListView) {
                android.widget.AbsListView lv = (android.widget.AbsListView) v;
                Object adapter = lv.getAdapter();
                if (adapter instanceof android.widget.WrapperListAdapter) {
                    adapter = ((android.widget.WrapperListAdapter) adapter).getWrappedAdapter();
                }
                if (adapter instanceof android.widget.BaseAdapter) {
                    ((android.widget.BaseAdapter) adapter).notifyDataSetChanged();
                    AppLogger.i(TAG, "【成功触发前台 AbsListView 刷新】" + adapter.getClass().getName());
                }
            }
        }
    }

    private static void refreshAvatarsRecursively(ViewGroup vg) {
        if (vg == null) return;
        Object tag = vg.getTag(ID_CUSTOM_AVATAR_TAG);
        if (tag instanceof String) {
            String targetId = (String) tag;
            String customPath = RemoteConfigHelper.getCustomAvatarPath(targetId);
            if (customPath != null) {
                Bitmap bm = getOrCreateCircleBitmap(targetId, customPath);
                applyOverlayToContainer(vg, targetId, bm);
            } else {
                applyOverlayToContainer(vg, null, null);
            }
        }
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child instanceof ViewGroup) {
                refreshAvatarsRecursively((ViewGroup) child);
            }
        }
    }

    private static volatile long sLastObservedConfigTime = 0L;

    private static boolean checkAndReloadConfigOnResume(Context context) {
        try {
            File configFile = com.emoji.reactor.util.StoragePaths.getQqMediaConfigFile();
            if (!configFile.exists() || configFile.length() == 0) {
                configFile = com.emoji.reactor.util.StoragePaths.getSharedConfigFile();
            }
            if (!configFile.exists() || configFile.length() == 0) {
                configFile = com.emoji.reactor.util.StoragePaths.getSafeMediaConfigFile();
            }
            if (configFile.exists() && configFile.length() > 0) {
                long mod = configFile.lastModified();
                if (mod > 0 && mod != sLastObservedConfigTime) {
                    sLastObservedConfigTime = mod;
                    AppLogger.i(TAG, "【感知到外部配置更新，切回前台即时热重载】mod=" + mod);
                    clearCircleCache();
                    RemoteConfigHelper.invalidateCache();
                    RemoteConfigHelper.reloadFullConfig(context);
                    UidUinHelper.warmUpConfiguredUins();
                    return true;
                }
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "checkAndReloadConfigOnResume 安全跳过: " + e.getMessage());
        }
        return false;
    }

    private void hookProfileActivity(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isEnabledRuntime()) return;
                    Activity act = (Activity) param.thisObject;
                    sCurrentResumedActivity = new WeakReference<>(act);

                    // 每次用户从外部切回 QQ，自动秒级感知磁盘配置变更（发生变更时才触发重绘）
                    boolean configChanged = checkAndReloadConfigOnResume(act);
                    if (configChanged) {
                        refreshVisibleAvatarsOnForeground();
                    }

                    String actName = act.getClass().getName();
                    if (!actName.contains("Profile") && !actName.contains("Card")
                            && !actName.contains("QZone") && !actName.contains("Qzone")) return;

                    Intent it = act.getIntent();
                    String uin = getUinFromIntent(it);
                    if (uin == null) {
                        // 仅当配置真实发生变动时，才触发全量刷新 Adapter，避免日常切前台打断动画
                        if (configChanged) {
                            View decor = act.getWindow().getDecorView();
                            if (decor instanceof ViewGroup) {
                                refreshAllListAdapters((ViewGroup) decor);
                                AppLogger.d(TAG, "【空间Activity感知配置更新，触发全量刷新】" + actName);
                            }
                        }
                        return;
                    }

                    String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                    Bitmap circleBm = customPath != null ? getOrCreateCircleBitmap(uin, customPath) : null;

                    // 仅定位专属 AvatarLayout (资源 ID: dk3)，绝不触碰相册与照片墙
                    View decor = act.getWindow().getDecorView();
                    if (decor instanceof ViewGroup) {
                        final ViewGroup finalDecor = (ViewGroup) decor;
                        final String finalUin = uin;
                        final Bitmap finalBm = circleBm;
                        findAndApplyAvatarLayoutOnly(finalDecor, finalUin, finalBm);
                        finalDecor.post(new Runnable() {
                            @Override
                            public void run() {
                                findAndApplyAvatarLayoutOnly(finalDecor, finalUin, finalBm);
                            }
                        });
                        finalDecor.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                findAndApplyAvatarLayoutOnly(finalDecor, finalUin, finalBm);
                            }
                        }, 250);
                    }
                }
            });
            AppLogger.i(TAG, "已成功挂载 ProfileActivity.onPostResume 资料卡探针！");
        } catch (Throwable t) {
            AppLogger.d(TAG, "hookProfileActivity 异常: " + t.getMessage());
        }
    }

    private static void findAndApplyAvatarLayoutOnly(ViewGroup root, String uin, Bitmap circleBm) {
        if (root == null) return;
        String cls = root.getClass().getName();
        String idName = "";
        try {
            if (root.getId() != View.NO_ID && root.getResources() != null) {
                idName = root.getResources().getResourceEntryName(root.getId()).toLowerCase();
            }
        } catch (Exception e) {
            // 试探资源名
        }

        if (cls.contains("AvatarLayout") || idName.contains("dk3")
                || cls.contains("QzoneAvatar") || cls.contains("QZoneAvatar")
                || cls.contains("FeedProAvatar") || cls.contains("AvatarView")
                || (cls.contains("HeaderContainer") && (idName.contains("avatar") || idName.contains("face") || idName.contains("head")))) {
            applyOverlayToContainer(root, uin, circleBm);
            AppLogger.i(TAG, "【资料卡/空间DecorView精准定位AvatarLayout成功】UIN=" + uin);
            return;
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) {
                findAndApplyAvatarLayoutOnly((ViewGroup) child, uin, circleBm);
            }
        }
    }

    /**
     * 防线 7：NT 内核服务单条与批量路径拦截 (IKernelAvatarService$CppProxy)
     */
    private void hookKernelAvatarService(ClassLoader cl) {
        String[] targetClasses = {
                "com.tencent.qqnt.kernel.nativeinterface.IKernelAvatarService$CppProxy"
        };

        for (String clsName : targetClasses) {
            try {
                Class<?> serviceClass = XposedHelpers.findClassIfExists(clsName, cl);
                if (serviceClass == null) continue;

                int count = 0;
                for (Method m : serviceClass.getDeclaredMethods()) {
                    String mName = m.getName();

                    // 1. 单个路径查询
                    if (m.getReturnType() == String.class && (mName.contains("AvatarPath") || mName.contains("avatarPath"))) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                if (param.args == null || param.args.length == 0) return;
                                for (Object arg : param.args) {
                                    String uin = extractUinFromObject(arg);
                                    if (uin != null) {
                                        String custom = RemoteConfigHelper.getCustomAvatarPath(uin);
                                        if (custom != null) {
                                            param.setResult(custom);
                                            AppLogger.i(TAG, "【IKernelAvatarService重定向】ID=" + uin + " -> " + custom);
                                            return;
                                        }
                                    }
                                }
                            }
                        });
                        count++;
                    }

                    // 2. 批量路径查询
                    if (Map.class.isAssignableFrom(m.getReturnType()) && mName.contains("AvatarPath")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                if (param.args == null || param.args.length == 0) return;
                                Object listArg = null;
                                for (Object arg : param.args) {
                                    if (arg instanceof List) {
                                        listArg = arg;
                                        break;
                                    }
                                }
                                if (listArg == null) return;

                                List<?> list = (List<?>) listArg;
                                Map<Object, Object> resultMap = (param.getResult() instanceof Map)
                                        ? new HashMap<>((Map<?, ?>) param.getResult())
                                        : new HashMap<>();

                                boolean modified = false;
                                for (Object item : list) {
                                    String id = extractUinFromObject(item);
                                    if (id != null) {
                                        String custom = RemoteConfigHelper.getCustomAvatarPath(id);
                                        if (custom != null) {
                                            resultMap.put(item, custom);
                                            modified = true;
                                            AppLogger.i(TAG, "【IKernelAvatarService批量重定向】ID=" + id + " -> " + custom);
                                        }
                                    }
                                }
                                if (modified) {
                                    param.setResult(resultMap);
                                }
                            }
                        });
                        count++;
                    }
                }
                AppLogger.i(TAG, "已成功挂载 " + clsName + " 内核头像服务探针 (方法数: " + count + ")！");
            } catch (Throwable t) {
                AppLogger.e(TAG, "hookKernelAvatarService 异常: " + clsName, t);
            }
        }
    }

    private void hookAvatarDataService(ClassLoader cl) {
        try {
            Class<?> dataServiceClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.avatar.api.impl.QQAvatarDataServiceImpl", cl);
            if (dataServiceClass == null) return;

            int count = 0;
            for (Method m : dataServiceClass.getDeclaredMethods()) {
                if ("getCustomFaceFilePath".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isEnabledRuntime()) return;
                            if (param.args == null || param.args.length == 0) return;
                            for (Object arg : param.args) {
                                String uin = extractUinFromObject(arg);
                                if (uin != null) {
                                    String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                                    if (customPath != null) {
                                        param.setResult(customPath);
                                        return;
                                    }
                                }
                            }
                        }
                    });
                    count++;
                } else if ("isFaceFileExist".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isEnabledRuntime()) return;
                            if (param.args == null || param.args.length == 0) return;
                            for (Object arg : param.args) {
                                String uin = extractUinFromObject(arg);
                                if (uin != null) {
                                    String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                                    if (customPath != null) {
                                        param.setResult(Boolean.TRUE);
                                        return;
                                    }
                                }
                            }
                        }
                    });
                    count++;
                } else if ("getBitmapFromCache".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isEnabledRuntime()) return;
                            if (param.args == null || param.args.length == 0) return;
                            for (Object arg : param.args) {
                                String uin = extractUinFromObject(arg);
                                if (uin != null) {
                                    String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                                    if (customPath != null) {
                                        Bitmap circleBm = getOrCreateCircleBitmap(uin, customPath);
                                        if (circleBm != null) {
                                            param.setResult(circleBm);
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    });
                    count++;
                }
            }
            AppLogger.i(TAG, "已成功挂载 QQAvatarDataServiceImpl 头像服务探针 (方法数: " + count + ")！");
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookAvatarDataService 异常", t);
        }
    }

    private void hookFaceDecoder(ClassLoader cl) {
        String[] decoderClasses = {
                "com.tencent.mobileqq.app.face.FaceDecoder",
                "com.tencent.mobileqq.util.FaceDecoder",
                "com.tencent.mobileqq.avatar.api.impl.QQAvatarServiceImpl"
        };
        for (String clsName : decoderClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(clsName, cl);
                if (clazz == null) continue;
                int count = 0;
                for (Method m : clazz.getDeclaredMethods()) {
                    if (!sHookedMethodSet.add(m)) continue;
                    String mName = m.getName();
                    if (mName.equals("getBitmapFromCache") && Bitmap.class.isAssignableFrom(m.getReturnType())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                if (param.args == null || param.args.length == 0) return;
                                for (Object arg : param.args) {
                                    String uin = extractUinFromObject(arg);
                                    if (uin != null) {
                                        String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                                        if (customPath != null) {
                                            Bitmap circleBm = getOrCreateCircleBitmap(uin, customPath);
                                            if (circleBm != null) {
                                                param.setResult(circleBm);
                                                return;
                                            }
                                        }
                                    }
                                }
                            }
                        });
                        count++;
                    } else if ("onDecodeTaskCompleted".equals(mName) && m.getParameterTypes().length >= 4) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                if (param.args == null || param.args.length < 4) return;
                                String uin = extractUinFromObject(param.args[2]);
                                if (uin != null) {
                                    String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                                    if (customPath != null) {
                                        Bitmap circleBm = getOrCreateCircleBitmap(uin, customPath);
                                        if (circleBm != null) {
                                            param.args[3] = circleBm;
                                            AppLogger.i(TAG, "【FaceDecoder.onDecodeTaskCompleted 替换成功】UIN=" + uin);
                                        }
                                    }
                                }
                            }
                        });
                        count++;
                    }
                }
                AppLogger.i(TAG, "已成功挂载 " + clsName + " 头像缓存探针 (方法数: " + count + ")！");
            } catch (Throwable t) {
                AppLogger.d(TAG, "hookFaceDecoder 降级: " + clsName + ", " + t.getMessage());
            }
        }
    }

    private void hookRecentContactInfo(ClassLoader cl) {
        try {
            Class<?> recentClass = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo", cl);
            if (recentClass == null) return;

            Method getAvatarPathMethod = XposedHelpers.findMethodExactIfExists(recentClass, "getAvatarPath");
            if (getAvatarPathMethod != null) {
                XposedBridge.hookMethod(getAvatarPathMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        long peerUin = 0L;
                        String peerUid = null;
                        try {
                            peerUin = XposedHelpers.getLongField(param.thisObject, "peerUin");
                        } catch (Exception e) {
                            // 字段peerUin读取
                        }
                        try {
                            peerUid = (String) XposedHelpers.getObjectField(param.thisObject, "peerUid");
                        } catch (Exception e) {
                            // 字段peerUid读取
                        }
                        if (peerUin > 0 && peerUid != null) {
                            UidUinHelper.bindUinAndUid(String.valueOf(peerUin), peerUid);
                        }
                        String targetId = (peerUin > 0) ? String.valueOf(peerUin) : peerUid;
                        if (targetId != null) {
                            String customPath = RemoteConfigHelper.getCustomAvatarPath(targetId);
                            if (customPath != null) {
                                param.setResult(customPath);
                            }
                        }
                    }
                });
                AppLogger.i(TAG, "已成功挂载 RecentContactInfo#getAvatarPath 会话列表头像重定向探针！");
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookRecentContactInfo 异常", t);
        }
    }

    private void hookMemberInfo(ClassLoader cl) {
        try {
            Class<?> memberClass = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.kernel.nativeinterface.MemberInfo", cl);
            if (memberClass == null) return;

            Method getAvatarPathMethod = XposedHelpers.findMethodExactIfExists(memberClass, "getAvatarPath");
            if (getAvatarPathMethod != null) {
                XposedBridge.hookMethod(getAvatarPathMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        long uin = 0L;
                        String uid = null;
                        try {
                            uin = XposedHelpers.getLongField(param.thisObject, "uin");
                        } catch (Exception e) {
                            // 字段uin读取
                        }
                        try {
                            uid = (String) XposedHelpers.getObjectField(param.thisObject, "uid");
                        } catch (Exception e) {
                            // 字段uid读取
                        }
                        if (uin > 0 && uid != null) {
                            UidUinHelper.bindUinAndUid(String.valueOf(uin), uid);
                        }
                        String targetId = (uin > 0) ? String.valueOf(uin) : uid;
                        if (targetId != null) {
                            String customPath = RemoteConfigHelper.getCustomAvatarPath(targetId);
                            if (customPath != null) {
                                param.setResult(customPath);
                            }
                        }
                    }
                });
                AppLogger.i(TAG, "已成功挂载 MemberInfo#getAvatarPath 群成员头像重定向探针！");
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookMemberInfo 异常", t);
        }
    }

    private static final java.util.Set<Class<?>> hookedAdapterClassSet = Collections.synchronizedSet(new java.util.HashSet<>());

    private static void applyAvatarToRowView(View row, String uin, Bitmap bm) {
        if (row == null || uin == null || bm == null) return;
        // 1. 优先定位现代专属头像容器（QQProAvatarView / VasAvatar / AvatarLayout / RecentAvatarViewWrapper / AsyncImageView / FeedProAvatar / QzoneAvatar）
        for (View v : findAllViews(row)) {
            String clsName = v.getClass().getName();
            if (clsName.contains("QQProAvatarView") || clsName.contains("VasAvatar") || clsName.contains("AvatarLayout")
                    || clsName.contains("RecentAvatar") || clsName.contains("AsyncImageView") || clsName.contains("FeedProAvatar")
                    || clsName.contains("QzoneAvatar") || clsName.contains("AvatarView")) {
                if (v instanceof ViewGroup) {
                    applyOverlayToContainer((ViewGroup) v, uin, bm);
                    AppLogger.i(TAG, "【列表专属头像控件覆盖成功】UIN=" + uin);
                    return;
                } else if (v instanceof ImageView) {
                    v.setTag(ID_CUSTOM_AVATAR_TAG, uin);
                    ((ImageView) v).setImageBitmap(bm);
                    AppLogger.i(TAG, "【列表专属头像ImageView覆盖成功】UIN=" + uin);
                    return;
                }
            }
        }
        // 2. 传统/自定义 ImageView 兜底定位
        for (ImageView iv : findAllImageViews(row)) {
            String rName = "";
            try {
                if (iv.getId() != View.NO_ID && iv.getResources() != null) {
                    rName = iv.getResources().getResourceEntryName(iv.getId()).toLowerCase();
                }
            } catch (Exception e) {
                // 试探资源名
            }
            int w = iv.getWidth();
            boolean isSmallBadge = (w > 0 && w < 80) || rName.contains("badge") || rName.contains("vip")
                    || rName.contains("level") || rName.contains("icon") || rName.contains("arrow");
            boolean isAvatar = rName.contains("avatar") || rName.contains("head") || rName.contains("face")
                    || iv.getClass().getName().contains("Avatar")
                    || (w >= 80);
            if (!isSmallBadge && isAvatar) {
                iv.setTag(ID_CUSTOM_AVATAR_TAG, uin);
                iv.setImageBitmap(bm);
                AppLogger.i(TAG, "【列表Row绑定自定义头像成功】UIN=" + uin);
                break;
            }
        }
    }

    private static void clearAvatarFromRowView(View row) {
        if (row == null) return;
        for (View v : findAllViews(row)) {
            String clsName = v.getClass().getName();
            if (clsName.contains("QQProAvatarView") || clsName.contains("VasAvatar") || clsName.contains("AvatarLayout")
                    || clsName.contains("RecentAvatar") || clsName.contains("AsyncImageView") || clsName.contains("FeedProAvatar")
                    || clsName.contains("QzoneAvatar") || clsName.contains("AvatarView")) {
                if (v instanceof ViewGroup) {
                    applyOverlayToContainer((ViewGroup) v, null, null);
                } else if (v instanceof ImageView) {
                    v.setTag(ID_CUSTOM_AVATAR_TAG, null);
                }
            }
        }
    }

    private void hookDynamicRecyclerViewAdapter(Class<?> clazz) {
        if (clazz == null || !hookedAdapterClassSet.add(clazz)) return;
        try {
            Class<?> curr = clazz;
            while (curr != null && curr != Object.class) {
                String cName = curr.getName();
                if (cName.equals("androidx.recyclerview.widget.RecyclerView$Adapter")
                        || cName.equals("com.tencent.widget.RecyclerView$Adapter")) {
                    break;
                }
                for (Method m : curr.getDeclaredMethods()) {
                    if (Modifier.isAbstract(m.getModifiers())) continue;
                    if (!sHookedMethodSet.add(m)) continue;
                    if ("onBindViewHolder".equals(m.getName()) && m.getParameterTypes().length >= 2) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                if (param.args == null || param.args.length < 2) return;
                                Object holder = param.args[0];
                                if (!(param.args[1] instanceof Number)) return;
                                int pos = ((Number) param.args[1]).intValue();
                                Object adapter = param.thisObject;
                                String rawId = null;
                                try {
                                    Object item = XposedHelpers.callMethod(adapter, "getItem", pos);
                                    rawId = extractRawIdFromObject(item);
                                } catch (Exception e) {
                                    // 试探getItem
                                }
                                if (rawId == null) {
                                    rawId = extractRawIdFromObject(holder);
                                }
                                View row = null;
                                try {
                                    row = (View) XposedHelpers.getObjectField(holder, "itemView");
                                } catch (Exception e) {
                                    // 试探itemView
                                }
                                if (row != null) {
                                    if (rawId != null) {
                                        String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                        if (custom != null) {
                                            Bitmap bm = getOrCreateCircleBitmap(rawId, custom);
                                            if (bm != null) {
                                                applyAvatarToRowView(row, rawId, bm);
                                                AppLogger.i(TAG, "【动态RecyclerView适配器绑定成功】Adapter=" + adapter.getClass().getSimpleName() + ", ID=" + rawId);
                                            }
                                        } else {
                                            clearAvatarFromRowView(row);
                                        }
                                    } else {
                                        clearAvatarFromRowView(row);
                                    }
                                }
                            }
                        });
                    }
                }
                curr = curr.getSuperclass();
            }
            AppLogger.i(TAG, "已成功动态挂载 RecyclerView 适配器: " + clazz.getName());
        } catch (Throwable t) {
            AppLogger.d(TAG, "hookDynamicRecyclerViewAdapter 异常: " + clazz.getName() + ", " + t.getMessage());
        }
    }

    private void hookDynamicAdapterClass(Class<?> clazz) {
        if (clazz == null || !hookedAdapterClassSet.add(clazz)) return;
        try {
            Class<?> curr = clazz;
            while (curr != null && curr != Object.class) {
                String cName = curr.getName();
                if (cName.equals("android.widget.BaseAdapter") || cName.equals("android.widget.ListAdapter") || cName.equals("android.widget.Adapter")) {
                    break;
                }
                for (Method m : curr.getDeclaredMethods()) {
                    if (Modifier.isAbstract(m.getModifiers())) continue;
                    if (!sHookedMethodSet.add(m)) continue;
                    if ("getView".equals(mNameCheck(m)) && m.getParameterTypes().length == 3) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                View row = (View) param.getResult();
                                if (row == null) return;
                                Object adapter = param.thisObject;
                                int pos = (Integer) param.args[0];
                                String rawId = null;
                                try {
                                    Object item = XposedHelpers.callMethod(adapter, "getItem", pos);
                                    rawId = extractRawIdFromObject(item);
                                    if (rawId != null) {
                                        AppLogger.i(TAG, "【动态适配器命中getItem】pos=" + pos + ", rawId=" + rawId + ", item=" + item.getClass().getName());
                                    }
                                } catch (Exception e) {
                                    // 试探getItem
                                }
                                if (rawId != null) {
                                    String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                    if (custom != null) {
                                        Bitmap bm = getOrCreateCircleBitmap(rawId, custom);
                                        if (bm != null) {
                                            applyAvatarToRowView(row, rawId, bm);
                                        }
                                    } else {
                                        clearAvatarFromRowView(row);
                                    }
                                }
                            }
                        });
                    } else if ("getChildView".equals(mNameCheck(m)) && m.getParameterTypes().length == 5) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                View row = (View) param.getResult();
                                if (row == null) return;
                                Object adapter = param.thisObject;
                                int gPos = (Integer) param.args[0];
                                int cPos = (Integer) param.args[1];
                                String rawId = null;
                                try {
                                    Object item = XposedHelpers.callMethod(adapter, "getChild", gPos, cPos);
                                    rawId = extractRawIdFromObject(item);
                                } catch (Exception e) {
                                    // 试探getChild
                                }
                                if (rawId != null) {
                                    String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                    if (custom != null) {
                                        Bitmap bm = getOrCreateCircleBitmap(rawId, custom);
                                        if (bm != null) {
                                            applyAvatarToRowView(row, rawId, bm);
                                        }
                                    } else {
                                        clearAvatarFromRowView(row);
                                    }
                                }
                            }
                        });
                    }
                }
                curr = curr.getSuperclass();
            }
            AppLogger.i(TAG, "已成功动态挂载适配器: " + clazz.getName());
        } catch (Throwable t) {
            AppLogger.d(TAG, "hookDynamicAdapterClass 异常: " + clazz.getName() + ", " + t.getMessage());
        }
    }

    private static String mNameCheck(Method m) {
        return m != null ? m.getName() : "";
    }

    private void hookListAdapters(ClassLoader cl) {
        String[] adapterClasses = {
                "com.tencent.mobileqq.friend.group.GroupListAdapter",
                "com.tencent.mobileqq.adapter.ak",
                "com.tencent.mobileqq.activity.TroopMemberListActivity$u",
                "com.tencent.mobileqq.activity.TroopMemberListActivity$TroopMemberListAdapter",
                "com.tencent.mobileqq.activity.contacts.friend.BuddyListAdapter",
                "com.tencent.mobileqq.adapter.BuddyListAdapter",
                "com.tencent.mobileqq.troop.memberlist.TroopMemberListAdapter",
                "com.tencent.mobileqq.activity.TroopMemberListActivity"
        };
        for (String clsName : adapterClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(clsName, cl);
                if (clazz == null) continue;
                hookDynamicAdapterClass(clazz);
            } catch (Throwable t) {
                AppLogger.d(TAG, "hookListAdapters 降级: " + clsName + ", " + t.getMessage());
            }
        }

        // 终极武器：挂载各类 ListView / ExpandableListView 的 setAdapter，动态捕获任何界面的子适配器
        Class<?>[] listClasses = {
                android.widget.ListView.class,
                XposedHelpers.findClassIfExists("com.tencent.widget.ListView", cl),
                XposedHelpers.findClassIfExists("com.tencent.widget.AbsListView", cl),
                XposedHelpers.findClassIfExists("com.tencent.widget.ExpandableListView", cl)
        };
        for (Class<?> lc : listClasses) {
            if (lc == null) continue;
            try {
                for (Method m : lc.getDeclaredMethods()) {
                    if ("setAdapter".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object adapter = param.args[0];
                                if (adapter != null) {
                                    hookDynamicAdapterClass(adapter.getClass());
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "hook listClasses setAdapter 跳过: " + e.getMessage());
            }
        }

        // 终极武器 2：挂载 RecyclerView 的 setAdapter，动态捕获任何界面（主页会话列表、空间动态流等）的适配器
        Class<?>[] rvClasses = {
                XposedHelpers.findClassIfExists("androidx.recyclerview.widget.RecyclerView", cl),
                XposedHelpers.findClassIfExists("com.tencent.widget.RecyclerView", cl)
        };
        for (Class<?> rc : rvClasses) {
            if (rc == null) continue;
            try {
                for (Method m : rc.getDeclaredMethods()) {
                    if ("setAdapter".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object adapter = param.args[0];
                                if (adapter != null) {
                                    hookDynamicRecyclerViewAdapter(adapter.getClass());
                                }
                            }
                        });
                    }
                }
                AppLogger.i(TAG, "已成功挂载 RecyclerView.setAdapter 通用适配器捕获探针: " + rc.getName());
            } catch (Exception e) {
                AppLogger.d(TAG, "hook rvClasses setAdapter 跳过: " + e.getMessage());
            }
        }

        // 挂载好友列表核心解码回调 ak.onDecodeTaskCompleted
        try {
            Class<?> akClass = XposedHelpers.findClassIfExists("com.tencent.mobileqq.adapter.ak", cl);
            if (akClass != null) {
                for (Method m : akClass.getDeclaredMethods()) {
                    if ("onDecodeTaskCompleted".equals(m.getName()) && m.getParameterTypes().length >= 4) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                if (param.args == null || param.args.length < 4) return;
                                String uin = extractUinFromObject(param.args[2]);
                                if (uin != null) {
                                    String custom = RemoteConfigHelper.getCustomAvatarPath(uin);
                                    if (custom != null) {
                                        Bitmap circleBm = getOrCreateCircleBitmap(uin, custom);
                                        if (circleBm != null) {
                                            param.args[3] = circleBm;
                                            AppLogger.i(TAG, "【ak.onDecodeTaskCompleted 替换成功】UIN=" + uin);
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "hook akClass 跳过: " + e.getMessage());
        }

        // 精准挂载 TroopMemberListActivity 列表项赋值 setItemViewValue
        try {
            Class<?> troopMemberActClass = XposedHelpers.findClassIfExists("com.tencent.mobileqq.activity.TroopMemberListActivity", cl);
            if (troopMemberActClass != null) {
                for (Method m : troopMemberActClass.getDeclaredMethods()) {
                    if ("setItemViewValue".equals(m.getName()) && m.getParameterTypes().length >= 2) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                Object holder = param.args[0];
                                Object item = param.args[1];
                                String rawId = extractRawIdFromObject(item);
                                if (rawId == null) {
                                    rawId = extractRawIdFromObject(holder);
                                }
                                if (rawId != null) {
                                    String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                    View row = null;
                                    if (holder instanceof View) {
                                        row = (View) holder;
                                    } else if (holder != null) {
                                        try {
                                            row = (View) XposedHelpers.getObjectField(holder, "itemView");
                                        } catch (Exception e) {
                                            // 试探itemView
                                        }
                                        if (row == null) {
                                            try {
                                                row = (View) XposedHelpers.getObjectField(holder, "a");
                                            } catch (Exception e) {
                                                // 试探a
                                            }
                                        }
                                    }
                                    if (row != null) {
                                        if (custom != null) {
                                            Bitmap bm = getOrCreateCircleBitmap(rawId, custom);
                                            if (bm != null) {
                                                applyAvatarToRowView(row, rawId, bm);
                                                AppLogger.i(TAG, "【setItemViewValue成功注入群成员头像】ID=" + rawId);
                                            }
                                        } else {
                                            clearAvatarFromRowView(row);
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "hook troopMemberActClass 跳过: " + e.getMessage());
        }
    }

    private void hookLibra(ClassLoader cl) {
        if (cl == null) return;
        try {
            Class<?> optionClass = XposedHelpers.findClassIfExists("com.tencent.libra.request.Option", cl);
            if (optionClass == null) return;

            XC_MethodHook urlHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isEnabledRuntime()) return;
                    if (param.args == null || param.args.length == 0) return;
                    Object arg = param.args[0];
                    if (!(arg instanceof String)) return;
                    String urlStr = (String) arg;
                    if (urlStr.isEmpty()) return;

                    boolean isAvatarUrl = urlStr.contains("qlogo") || urlStr.contains("avatar") 
                            || urlStr.contains("head") || urlStr.contains("face");
                    boolean isChatPic = urlStr.contains("gchatpic") || urlStr.contains("c2cpic") 
                            || urlStr.contains("chatthumb") || urlStr.contains("offpic");
                    if (!isAvatarUrl || isChatPic) return;

                    // 1. 优先尝试从 Option 实例的 targetView 或 model 提取目标 UIN
                    String targetId = null;
                    try {
                        Object v = XposedHelpers.callMethod(param.thisObject, "getTargetView");
                        if (v instanceof View) targetId = extractRawIdFromObject(v);
                    } catch (Exception ignored) {}
                    if (targetId == null) {
                        try {
                            Object v = XposedHelpers.getObjectField(param.thisObject, "targetView");
                            if (v instanceof View) targetId = extractRawIdFromObject(v);
                        } catch (Exception ignored) {}
                    }
                    if (targetId == null) {
                        try {
                            Object m = XposedHelpers.getObjectField(param.thisObject, "model");
                            targetId = extractRawIdFromObject(m);
                        } catch (Exception ignored) {}
                    }
                    if (targetId != null) {
                        String customPath = RemoteConfigHelper.getCustomAvatarPath(targetId);
                        if (customPath != null) {
                            learnOidbToken(urlStr, targetId);
                            param.args[0] = "file://" + customPath;
                            AppLogger.i(TAG, "【Libra Option.setUrl 命中TargetView重定向】UIN=" + targetId + " -> file://" + customPath);
                            return;
                        }
                    }

                    // 2. 检查 URL 是否直接包含 UIN 或已学习到的 OIDB Token
                    for (String uin : RemoteConfigHelper.getAllConfiguredUins()) {
                        String uid = UidUinHelper.getUidFromUin(uin);
                        boolean matches = urlStr.contains(uin) || (uid != null && !uid.isEmpty() && urlStr.contains(uid));
                        if (!matches && !sOidbTokenToUinMap.isEmpty()) {
                            for (Map.Entry<String, String> tokenEntry : sOidbTokenToUinMap.entrySet()) {
                                if (uin.equals(tokenEntry.getValue()) && urlStr.contains(tokenEntry.getKey())) {
                                    matches = true;
                                    break;
                                }
                            }
                        }
                        if (matches) {
                            String customPath = RemoteConfigHelper.getCustomAvatarPath(uin);
                            if (customPath != null) {
                                File f = new File(customPath);
                                if (f.exists() && f.length() > 0) {
                                    param.args[0] = "file://" + customPath;
                                    AppLogger.i(TAG, "【Libra Option.setUrl 重定向成功】UIN=" + uin + " -> " + customPath);
                                    return;
                                }
                            }
                        }
                    }
                }
            };

            java.util.Set<Method> allMethods = new java.util.HashSet<>();
            allMethods.addAll(java.util.Arrays.asList(optionClass.getDeclaredMethods()));
            allMethods.addAll(java.util.Arrays.asList(optionClass.getMethods()));
            for (Method m : allMethods) {
                String mName = m.getName();
                if (("setUrl".equals(mName) || "setRequestUrl".equals(mName) || "load".equals(mName))
                        && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class
                        && sHookedMethodSet.add(m)) {
                    XposedBridge.hookMethod(m, urlHook);
                }
            }
            AppLogger.i(TAG, "已成功挂载 Tencent Libra 图片框架探针！");
        } catch (Throwable t) {
            AppLogger.d(TAG, "hookLibra 异常: " + t.getMessage());
        }
    }

    private void hookQzone(ClassLoader cl) {
        if (cl == null) return;
        String[] qzoneClasses = {
                "com.qzone.reborn.feedpro.widget.avatar.QzoneFeedProAvatarView",
                "com.qzone.reborn.feedpro.widget.avatar.QzoneAvatarView",
                "com.qzone.module.feedcomponent.ui.AvatarView",
                "com.qzone.widget.AsyncImageView",
                "cooperation.qzone.util.QZoneFaceUtils",
                "cooperation.qzone.widget.QzoneAvatarView",
                "com.tencent.qzone.view.QzoneAvatarView",
                "com.tencent.qzone.image.ImageLoader",
                "com.qzone.module.feedcomponent.ui.FeedViewBuilder"
        };
        for (String clsName : qzoneClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(clsName, cl);
                if (clazz == null) continue;

                // 针对 FeedViewBuilder 动态流模型与视图挂载
                if ("com.qzone.module.feedcomponent.ui.FeedViewBuilder".equals(clsName)) {
                    for (Method m : clazz.getDeclaredMethods()) {
                        if ("setFeedViewData".equals(m.getName()) && sHookedMethodSet.add(m)) {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    if (!isEnabledRuntime()) return;
                                    if (param.args == null || param.args.length < 3) return;
                                    Object feedData = param.args[2];
                                    if (feedData == null) return;
                                    String rawId = extractRawIdFromObject(feedData);
                                    if (rawId != null) {
                                        String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                        if (custom != null) {
                                            mutateAvatarUrlInFeedData(feedData, rawId, "file://" + custom);
                                            Object feedView = param.args[1];
                                            if (feedView instanceof View) {
                                                Bitmap bm = getOrCreateCircleBitmap(rawId, custom);
                                                if (bm != null) {
                                                    applyAvatarToRowView((View) feedView, rawId, bm);
                                                    AppLogger.i(TAG, "【FeedViewBuilder注入头像成功】ID=" + rawId);
                                                }
                                            }
                                        }
                                    }
                                }
                            });
                        }
                    }
                    AppLogger.i(TAG, "已成功挂载 FeedViewBuilder#setFeedViewData 探针！");
                    continue;
                }

                // 针对 AsyncImageView 的精准 URL 拦截
                if ("com.qzone.widget.AsyncImageView".equals(clsName)) {
                    for (Method m : clazz.getDeclaredMethods()) {
                        String mName = m.getName();
                        if (mName.equals("onMeasure") || mName.equals("onLayout") || mName.equals("onDraw") || mName.equals("dispatchDraw")) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length > 0 && pts[0] == String.class && sHookedMethodSet.add(m)) {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    if (!isEnabledRuntime()) return;
                                    if (param.args == null || param.args.length == 0) return;
                                    Object arg0 = param.args[0];
                                    if (arg0 instanceof String) {
                                        String url = (String) arg0;
                                        for (String uin : RemoteConfigHelper.getAllConfiguredUins()) {
                                            boolean matches = url.contains(uin);
                                            if (!matches && !sOidbTokenToUinMap.isEmpty()) {
                                                for (Map.Entry<String, String> tokenEntry : sOidbTokenToUinMap.entrySet()) {
                                                    if (uin.equals(tokenEntry.getValue()) && url.contains(tokenEntry.getKey())) {
                                                        matches = true;
                                                        break;
                                                    }
                                                }
                                            }
                                            if (matches) {
                                                String custom = RemoteConfigHelper.getCustomAvatarPath(uin);
                                                if (custom != null) {
                                                    param.args[0] = "file://" + custom;
                                                    AppLogger.i(TAG, "【AsyncImageView URL重定向成功】UIN=" + uin + " -> file://" + custom);
                                                    return;
                                                }
                                            }
                                        }
                                    }
                                }
                            });
                        }
                    }
                    AppLogger.i(TAG, "已成功挂载 com.qzone.widget.AsyncImageView 探针！");
                    continue;
                }

                for (Method m : clazz.getDeclaredMethods()) {
                    if (!sHookedMethodSet.add(m)) continue;
                    String mName = m.getName();
                    if (Bitmap.class.isAssignableFrom(m.getReturnType())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                if (param.args == null || param.args.length == 0) return;
                                for (Object arg : param.args) {
                                    String rawId = extractRawIdFromObject(arg);
                                    if (rawId != null) {
                                        String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                        if (custom != null) {
                                            Bitmap bm = getOrCreateCircleBitmap(rawId, custom);
                                            if (bm != null) {
                                                param.setResult(bm);
                                                AppLogger.i(TAG, "【QZoneFaceUtils拦截成功】ID=" + rawId);
                                                return;
                                            }
                                        }
                                    }
                                }
                            }
                        });
                    } else if (String.class.isAssignableFrom(m.getReturnType()) && (mName.contains("Face") || mName.contains("Avatar") || mName.contains("Url") || mName.contains("url"))) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                if (param.args == null || param.args.length == 0) return;
                                String rawId = null;
                                for (Object arg : param.args) {
                                    rawId = extractRawIdFromObject(arg);
                                    if (rawId != null) break;
                                }
                                if (rawId != null) {
                                    String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                    if (custom != null) {
                                        Object orig = param.getResult();
                                        if (orig instanceof String) {
                                            learnOidbToken((String) orig, rawId);
                                        }
                                        param.setResult("file://" + custom);
                                        AppLogger.i(TAG, "【QZoneFaceUtils getFaceUrl 拦截成功】ID=" + rawId + " -> file://" + custom);
                                        return;
                                    }
                                }
                            }
                        });
                    } else if (mName.contains("Avatar") || mName.contains("Face") || mName.contains("Feed") || mName.contains("User")
                            || mName.contains("bind") || mName.contains("update") || mName.contains("load")) {
                        if (m.getParameterTypes().length == 0) continue;
                        if (mName.equals("onMeasure") || mName.equals("onLayout") || mName.equals("onDraw") || mName.equals("dispatchDraw")
                                || mName.equals("setImageBitmap") || mName.equals("setImageDrawable") || mName.equals("setImageURI") || mName.equals("setTag")) {
                            continue;
                        }

                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!isEnabledRuntime()) return;
                                if (Boolean.TRUE.equals(sIsSettingQzoneImage.get())) return;
                                try {
                                    sIsSettingQzoneImage.set(Boolean.TRUE);
                                    Object target = param.thisObject;
                                    String rawId = null;
                                    if (param.args != null) {
                                        for (Object arg : param.args) {
                                            rawId = extractRawIdFromObject(arg);
                                            if (rawId != null) {
                                                if (arg instanceof String) {
                                                    learnOidbToken((String) arg, rawId);
                                                }
                                                break;
                                            }
                                        }
                                    }
                                    if (rawId == null) {
                                        rawId = extractRawIdFromObject(target);
                                    }
                                    if (rawId != null) {
                                        String custom = RemoteConfigHelper.getCustomAvatarPath(rawId);
                                        if (custom != null) {
                                            Bitmap bm = getOrCreateCircleBitmap(rawId, custom);
                                            if (bm != null) {
                                                if (target instanceof ViewGroup) {
                                                    applyOverlayToContainer((ViewGroup) target, rawId, bm);
                                                } else if (target instanceof ImageView) {
                                                    ((ImageView) target).setTag(ID_CUSTOM_AVATAR_TAG, rawId);
                                                    ((ImageView) target).setImageBitmap(bm);
                                                }
                                                AppLogger.i(TAG, "【QzoneAvatarView设置成功】ID=" + rawId);
                                            }
                                        } else {
                                            if (target instanceof ViewGroup) {
                                                applyOverlayToContainer((ViewGroup) target, null, null);
                                            } else if (target instanceof ImageView) {
                                                ((ImageView) target).setTag(ID_CUSTOM_AVATAR_TAG, null);
                                            }
                                        }
                                    }
                                } finally {
                                    sIsSettingQzoneImage.remove();
                                }
                            }
                        });
                    }
                }
                AppLogger.i(TAG, "已成功挂载 " + clsName + " Qzone探针！");
            } catch (Throwable t) {
                AppLogger.d(TAG, "hookQzone 降级: " + clsName + ", " + t.getMessage());
            }
        }
    }

    private static void mutateAvatarUrlInFeedData(Object obj, String rawId, String newUrl) {
        mutateAvatarUrlInFeedData(obj, rawId, newUrl, 0);
    }

    private static void mutateAvatarUrlInFeedData(Object obj, String rawId, String newUrl, int depth) {
        if (obj == null || depth > 3) return;
        try {
            String[] urlFields = {"avatarUrl", "logo", "iconUrl", "url", "headUrl", "portrait"};
            for (String fName : urlFields) {
                try {
                    Object v = XposedHelpers.getObjectField(obj, fName);
                    if (v instanceof String) {
                        String oldUrl = (String) v;
                        if (!oldUrl.startsWith("file://")) {
                            learnOidbToken(oldUrl, rawId);
                        }
                        XposedHelpers.setObjectField(obj, fName, newUrl);
                    }
                } catch (Exception ignored) {}
            }
            String[] subFields = {"cellUserInfo", "user", "userInfo", "cellUser", "userKey", "commInfo"};
            for (String sf : subFields) {
                try {
                    Object sub = XposedHelpers.getObjectField(obj, sf);
                    if (sub != null && sub != obj) {
                        mutateAvatarUrlInFeedData(sub, rawId, newUrl, depth + 1);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static void hookStUser(ClassLoader cl) {
        if (cl == null) return;
        try {
            Class<?> stUserClass = XposedHelpers.findClassIfExists("com.tencent.qqnt.kernel.nativeinterface.StUser", cl);
            if (stUserClass != null) {
                Method getPortraitMethod = XposedHelpers.findMethodExactIfExists(stUserClass, "getPortrait");
                if (getPortraitMethod != null && sHookedMethodSet.add(getPortraitMethod)) {
                    XposedBridge.hookMethod(getPortraitMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isEnabledRuntime()) return;
                            try {
                                Object obj = param.thisObject;
                                if (obj == null) return;
                                String uin = extractRawIdFromObject(XposedHelpers.getObjectField(obj, "uin"));
                                String uid = extractRawIdFromObject(XposedHelpers.getObjectField(obj, "uid"));
                                if (uin != null && uid != null) {
                                    UidUinHelper.bindUinAndUid(uin, uid);
                                }
                                String targetId = (uin != null && !uin.isEmpty()) ? uin : uid;
                                if (targetId == null) return;
                                String custom = RemoteConfigHelper.getCustomAvatarPath(targetId);
                                if (custom != null) {
                                    Object orig = param.getResult();
                                    if (orig instanceof String) {
                                        learnOidbToken((String) orig, targetId);
                                    }
                                    String localUri = "file://" + custom;
                                    param.setResult(localUri);
                                    AppLogger.i(TAG, "【StUser.getPortrait 源头拦截成功】ID=" + targetId + " -> " + localUri);
                                }
                            } catch (Throwable t) {
                                AppLogger.d(TAG, "getPortrait hook 安全跳过: " + t.getMessage());
                            }
                        }
                    });
                }

                Method getUinMethod = XposedHelpers.findMethodExactIfExists(stUserClass, "getUin");
                if (getUinMethod != null && sHookedMethodSet.add(getUinMethod)) {
                    XposedBridge.hookMethod(getUinMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isEnabledRuntime()) return;
                            try {
                                Object obj = param.thisObject;
                                if (obj == null) return;
                                String uin = extractRawIdFromObject(param.getResult());
                                String uid = extractRawIdFromObject(XposedHelpers.getObjectField(obj, "uid"));
                                if (uin != null && uid != null) {
                                    UidUinHelper.bindUinAndUid(uin, uid);
                                }
                            } catch (Throwable t) {
                                AppLogger.d(TAG, "getUin hook 安全跳过: " + t.getMessage());
                            }
                        }
                    });
                }
                AppLogger.i(TAG, "已成功挂载 NT StUser 空间/动态用户模型探针！");
            }

            Class<?> stFeedCellUserInfoClass = XposedHelpers.findClassIfExists("com.tencent.qqnt.kernel.nativeinterface.StFeedCellUserInfo", cl);
            if (stFeedCellUserInfoClass != null) {
                Method getUserMethod = XposedHelpers.findMethodExactIfExists(stFeedCellUserInfoClass, "getUser");
                if (getUserMethod != null && sHookedMethodSet.add(getUserMethod)) {
                    XposedBridge.hookMethod(getUserMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isEnabledRuntime()) return;
                            try {
                                Object user = param.getResult();
                                if (user == null) return;
                                String uin = extractRawIdFromObject(XposedHelpers.getObjectField(user, "uin"));
                                String uid = extractRawIdFromObject(XposedHelpers.getObjectField(user, "uid"));
                                String targetId = (uin != null && !uin.isEmpty()) ? uin : uid;
                                if (targetId != null) {
                                    String custom = RemoteConfigHelper.getCustomAvatarPath(targetId);
                                    if (custom != null) {
                                        Object portraitObj = XposedHelpers.getObjectField(user, "portrait");
                                        if (portraitObj instanceof String) {
                                            String portrait = (String) portraitObj;
                                            if (!portrait.startsWith("file://")) {
                                                learnOidbToken(portrait, targetId);
                                            }
                                        }
                                        XposedHelpers.setObjectField(user, "portrait", "file://" + custom);
                                        AppLogger.i(TAG, "【StFeedCellUserInfo.getUser 篡改 portrait 成功】ID=" + targetId);
                                    }
                                }
                            } catch (Throwable t) {
                                AppLogger.d(TAG, "getUser hook 安全跳过: " + t.getMessage());
                            }
                        }
                    });
                }
                AppLogger.i(TAG, "已成功挂载 NT StFeedCellUserInfo 探针！");
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "hookStUser 异常: " + t.getMessage());
        }
    }

    private static Bitmap getOrCreateCircleBitmap(String uin, String filePath) {
        if (uin == null || filePath == null) return null;
        File f = new File(filePath);
        if (!f.exists() || f.length() == 0) return null;

        long currentMod = f.lastModified();
        Long cachedMod = avatarLastModifiedMap.get(uin);
        if (cachedMod != null && cachedMod != currentMod) {
            circleBitmapCache.remove(uin);
            avatarLastModifiedMap.remove(uin);
        }

        Bitmap cached = circleBitmapCache.get(uin);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, opts);
            int origW = opts.outWidth;
            int origH = opts.outHeight;
            int sampleSize = 1;
            while ((origW / sampleSize) > 512 || (origH / sampleSize) > 512) {
                sampleSize *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap raw = BitmapFactory.decodeFile(filePath, opts);
            if (raw == null) return null;

            Bitmap circle = createCircleBitmap(raw);
            if (raw != circle) {
                raw.recycle();
            }
            if (circle != null) {
                circleBitmapCache.put(uin, circle);
                avatarLastModifiedMap.put(uin, currentMod);
            }
            return circle;
        } catch (Throwable t) {
            AppLogger.e(TAG, "getOrCreateCircleBitmap 异常: " + uin, t);
            return null;
        }
    }

    private static Bitmap createCircleBitmap(Bitmap source) {
        if (source == null) return null;
        int size = Math.min(source.getWidth(), source.getHeight());
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        float r = size / 2f;
        canvas.drawCircle(r, r, r, paint);
        return output;
    }
}
