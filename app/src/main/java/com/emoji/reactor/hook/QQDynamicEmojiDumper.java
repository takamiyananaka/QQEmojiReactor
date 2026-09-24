package com.emoji.reactor.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;

import com.emoji.reactor.data.ConfigContentProvider;
import com.emoji.reactor.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * QQ 运行时动态表情全量与实时捕获总引擎 (QQDynamicEmojiDumper)
 * 双轨驱动：
 * 轨道 1：启动主动全量扫描 QQSysAndEmojiResMgr 与 QQSysFaceUtil，提取 QQ 核心表情与官方超清 Drawable
 * 轨道 2：实时消息气泡与 AniStickerLottieView 嗅探，一旦群聊渲染新表情立即捕获并导出
 * 输出目录：/sdcard/Android/media/com.tencent.mobileqq/live_emojis/ (QQ 自身拥有合法写入权限，零权限拦截)
 */
public class QQDynamicEmojiDumper {

    private static final String TAG = "QQEmojiReactor_Dumper";
    public static final String LIVE_EMOJIS_DIR = "/sdcard/Download/QQEmojiReactor/live_emojis";
    public static final String LIVE_INDEX_FILE = LIVE_EMOJIS_DIR + "/live_emojis_index.json";
    public static final String QQ_LEGACY_DUMP_PATH = "/sdcard/Android/media/com.tencent.mobileqq/qq_live_faces.json";
    public static final String QQ_LEGACY_MEDIA_DIR = "/sdcard/Android/media/com.tencent.mobileqq/live_emojis";

    private static HandlerThread dumpThread;
    private static Handler dumpHandler;
    private static final Set<String> discoveredKeys = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, JSONObject> activeIndexMap = new ConcurrentHashMap<>();

    private static ClassLoader hostClassLoader;
    private static volatile boolean isScanning = false;

    public static synchronized void init(ClassLoader cl) {
        if (cl == null) return;
        hostClassLoader = cl;

        if (dumpThread == null) {
            dumpThread = new HandlerThread("EmojiDumper-Thread");
            dumpThread.start();
            dumpHandler = new Handler(dumpThread.getLooper());
        }

        // 初始化加载磁盘已有索引
        loadExistingIndex();

        // 启动轨道 1：延迟 3 秒执行全量表情池扫描（留足 QQ 启动初始化时间）
        dumpHandler.postDelayed(() -> doFullScanFromHost(cl, 1), 3000);

        // 启动轨道 2：挂载消息气泡与动画表情实时嗅探探针
        attachRealtimeSniffers(cl);
    }

    /**
     * 轨道 1：主动全量扫描
     */
    private static void doFullScanFromHost(ClassLoader cl, int attempt) {
        if (isScanning) return;
        isScanning = true;
        try {
            AppLogger.i(TAG, "开始执行全量表情池主动扫描 (轮次: " + attempt + ")...");
            Context hostContext = getHostContext(cl);

            // 1. 扫描 QQSysFaceUtil 核心库
            scanSysFaceUtil(cl, hostContext);

            // 2. 扫描 QQSysAndEmojiResMgr 核心池
            scanSysAndEmojiResMgr(cl, hostContext);

            // 持久化当前所有发现的表情
            flushIndexToFile(hostContext);

            // 若首次获取数量较少，在 8 秒后进行二次增量补充扫描（以防云端热更新包延迟就绪）
            if (attempt == 1 && activeIndexMap.size() < 200 && dumpHandler != null) {
                dumpHandler.postDelayed(() -> doFullScanFromHost(cl, 2), 8000);
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "doFullScanFromHost 执行异常", t);
        } finally {
            isScanning = false;
        }
    }

    /**
     * 轨道 2：实时消息气泡与动画表情嗅探器
     */
    private static void attachRealtimeSniffers(ClassLoader cl) {
        // 嗅探 A：Hook AniStickerLottieView 与动画表情渲染组件
        try {
            Class<?> lottieViewClass = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.aio.anisticker.view.AniStickerLottieView", cl);
            if (lottieViewClass != null) {
                for (Method m : lottieViewClass.getDeclaredMethods()) {
                    // 拦截设置数据、加载动画、渲染等方法
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length >= 1) {
                        try {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    handleAniStickerMethodInvoked(param);
                                }
                            });
                        } catch (Throwable ignored) {
                        }
                    }
                }
                AppLogger.i(TAG, "已成功挂载 AniStickerLottieView 动画表情实时嗅探探针！");
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "挂载 AniStickerLottieView 探针异常", t);
        }

        // 嗅探 B：Hook AIO 消息项绑定过程，直接从 MsgRecord 的 FaceElement 提取
        try {
            Class<?> baseHolderClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent", cl);
            if (baseHolderClass != null) {
                for (Method m : baseHolderClass.getDeclaredMethods()) {
                    if (m.getName().toLowerCase().contains("bind") && m.getParameterTypes().length > 0) {
                        try {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    handleComponentBindInvoked(param);
                                }
                            });
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "挂载 BaseContentComponent 绑定探针异常", t);
        }
    }

    private static void handleAniStickerMethodInvoked(XC_MethodHook.MethodHookParam param) {
        if (param == null || param.args == null) return;
        for (Object arg : param.args) {
            if (arg == null) continue;
            try {
                // 探测 arg 中的 stickerId / faceId / id / name
                String stickerId = findStringOrNumberField(arg, "stickerId", "faceId", "id", "aniStickerId");
                String name = findStringOrNumberField(arg, "name", "text", "description", "qdes");
                if (stickerId != null && !stickerId.isEmpty()) {
                    Context ctx = null;
                    if (param.thisObject instanceof View) {
                        ctx = ((View) param.thisObject).getContext();
                    }
                    onEmojiDiscovered(stickerId, 1L, name, null, null, ctx);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void handleComponentBindInvoked(XC_MethodHook.MethodHookParam param) {
        if (param == null || param.args == null) return;
        for (Object arg : param.args) {
            if (arg == null) continue;
            try {
                Object msgRecord = null;
                if (arg.getClass().getName().contains("AIOMsgItem")) {
                    msgRecord = XposedHelpers.callMethod(arg, "getMsgRecord");
                } else if (arg.getClass().getName().contains("MsgRecord")) {
                    msgRecord = arg;
                }

                if (msgRecord != null) {
                    sniffMsgRecordElements(msgRecord);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static void sniffMsgRecordElements(Object msgRecord) {
        if (msgRecord == null) return;
        try {
            List<?> elements = (List<?>) XposedHelpers.getObjectField(msgRecord, "elements");
            if (elements == null || elements.isEmpty()) return;

            for (Object elem : elements) {
                if (elem == null) continue;
                try {
                    Object faceElem = XposedHelpers.getObjectField(elem, "faceElement");
                    if (faceElem != null) {
                        int faceIndex = XposedHelpers.getIntField(faceElem, "faceIndex");
                        int faceType = XposedHelpers.getIntField(faceElem, "faceType");
                        String faceText = (String) XposedHelpers.getObjectField(faceElem, "faceText");
                        if (faceText != null) faceText = faceText.replace("/", "");

                        long type = (faceType == 2 || faceIndex >= 1000) ? 2L : 1L;
                        onEmojiDiscovered(String.valueOf(faceIndex), type, faceText, null, null, null);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 统一入口：发现并记录一个新表情
     */
    public static void onEmojiDiscovered(String emojiId, long emojiType, String name, String sourcePath, Drawable optionalDrawable, Context context) {
        if (emojiId == null || emojiId.trim().isEmpty()) return;
        final String cleanId = emojiId.trim();
        final String key = emojiType + "_" + cleanId;

        if (discoveredKeys.contains(key) && activeIndexMap.containsKey(key)) {
            return;
        }

        if (dumpHandler != null) {
            dumpHandler.post(() -> {
                try {
                    File liveDir = new File(LIVE_EMOJIS_DIR);
                    if (!liveDir.exists()) liveDir.mkdirs();

                    String finalPath = null;
                    File targetImgFile = new File(liveDir, "face_" + cleanId + ".png");

                    // 1. 如果已有图片文件或传入了 Drawable，则生成对应 PNG
                    if (optionalDrawable != null && !targetImgFile.exists()) {
                        Bitmap bm = drawableToBitmap(optionalDrawable);
                        if (bm != null) {
                            saveBitmapToPng(bm, targetImgFile);
                            finalPath = targetImgFile.getAbsolutePath();
                        }
                    } else if (sourcePath != null && new File(sourcePath).exists() && !targetImgFile.exists()) {
                        copyFile(new File(sourcePath), targetImgFile);
                        finalPath = targetImgFile.getAbsolutePath();
                    } else if (targetImgFile.exists()) {
                        finalPath = targetImgFile.getAbsolutePath();
                    }

                    // 2. 如果缺少图片，尝试从 QQ 的 QQSysFaceUtil 实时生成一次 Drawable
                    if (finalPath == null && emojiType == 1L && hostClassLoader != null) {
                        try {
                            int fid = Integer.parseInt(cleanId);
                            Drawable d = loadSysFaceDrawable(hostClassLoader, context, fid);
                            if (d != null) {
                                Bitmap bm = drawableToBitmap(d);
                                if (bm != null) {
                                    saveBitmapToPng(bm, targetImgFile);
                                    finalPath = targetImgFile.getAbsolutePath();
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    // 3. 如果缺少名称，尝试从 QQSysFaceUtil 实时提取中文描述
                    String finalName = name != null ? name.trim() : "";
                    if (finalName.isEmpty() && emojiType == 1L && hostClassLoader != null) {
                        try {
                            int fid = Integer.parseInt(cleanId);
                            finalName = loadSysFaceDescription(hostClassLoader, fid);
                        } catch (Throwable ignored) {
                        }
                    }

                    JSONObject itemObj = new JSONObject();
                    itemObj.put("emojiId", cleanId);
                    itemObj.put("emojiType", emojiType);
                    itemObj.put("name", finalName);
                    if (finalPath != null) {
                        itemObj.put("path", finalPath);
                    }
                    itemObj.put("timestamp", System.currentTimeMillis());

                    activeIndexMap.put(key, itemObj);
                    discoveredKeys.add(key);

                    AppLogger.i(TAG, "【动态捕获新表情】ID: " + cleanId + ", Type: " + emojiType + ", 名字: " + finalName + ", 图片: " + (finalPath != null));

                    // 写入磁盘
                    flushIndexToFile(context != null ? context : getHostContext(hostClassLoader));

                } catch (Throwable t) {
                    AppLogger.e(TAG, "onEmojiDiscovered 处理异常", t);
                }
            });
        }
    }

    private static void scanSysFaceUtil(ClassLoader cl, Context context) {
        String[] utilClassNames = {
                "com.tencent.qqnt.emotion.utils.QQSysFaceUtil",
                "com.tencent.mobileqq.emoticon.QQSysFaceUtil"
        };

        for (String clsName : utilClassNames) {
            try {
                Class<?> utilCls = XposedHelpers.findClassIfExists(clsName, cl);
                if (utilCls == null) continue;

                // 遍历获取全量 ID 列表的方法
                for (Method m : utilCls.getDeclaredMethods()) {
                    if (List.class.isAssignableFrom(m.getReturnType()) && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        Object res = m.invoke(null);
                        if (res instanceof List) {
                            List<?> idList = (List<?>) res;
                            AppLogger.i(TAG, "从 " + clsName + "#" + m.getName() + " 捕获到基础 ID 总数: " + idList.size());
                            for (Object idObj : idList) {
                                if (idObj instanceof Number) {
                                    int id = ((Number) idObj).intValue();
                                    String name = loadSysFaceDescription(cl, id);
                                    Drawable d = loadSysFaceDrawable(cl, context, id);
                                    onEmojiDiscovered(String.valueOf(id), 1L, name, null, d, context);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                AppLogger.d(TAG, "扫描 " + clsName + " 异常: " + t.getMessage());
            }
        }
    }

    private static void scanSysAndEmojiResMgr(ClassLoader cl, Context context) {
        try {
            Class<?> resMgrCls = XposedHelpers.findClassIfExists("com.tencent.mobileqq.emoticon.QQSysAndEmojiResMgr", cl);
            if (resMgrCls == null) return;

            Object mgrInstance = XposedHelpers.callStaticMethod(resMgrCls, "getInstance");
            if (mgrInstance == null) return;

            Object sysFaceResImpl = XposedHelpers.callMethod(mgrInstance, "getResImpl", 1);
            if (sysFaceResImpl == null) return;

            Class<?> c = sysFaceResImpl.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object mapVal = f.get(sysFaceResImpl);
                        if (mapVal instanceof Map) {
                            Map<?, ?> map = (Map<?, ?>) mapVal;
                            for (Map.Entry<?, ?> entry : map.entrySet()) {
                                Object configItem = entry.getValue();
                                if (configItem == null) continue;
                                try {
                                    String qsidStr = findStringOrNumberField(configItem, "QSid", "id");
                                    String qdes = findStringOrNumberField(configItem, "QDes", "name");
                                    if (qdes != null) qdes = qdes.replace("/", "");

                                    if (qsidStr != null && !qsidStr.trim().isEmpty()) {
                                        int qsid = Integer.parseInt(qsidStr.trim());
                                        String localPath = null;
                                        try {
                                            localPath = (String) XposedHelpers.callStaticMethod(resMgrCls, "getFullResPath", 2, String.format("/s%d.png", qsid));
                                        } catch (Throwable ignored) {
                                        }

                                        Drawable d = loadSysFaceDrawable(cl, context, qsid);
                                        onEmojiDiscovered(String.valueOf(qsid), 1L, qdes, localPath, d, context);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "scanSysAndEmojiResMgr 扫描跳过: " + t.getMessage());
        }
    }

    private static String loadSysFaceDescription(ClassLoader cl, int id) {
        String[] utilClassNames = {
                "com.tencent.qqnt.emotion.utils.QQSysFaceUtil",
                "com.tencent.mobileqq.emoticon.QQSysFaceUtil"
        };
        for (String clsName : utilClassNames) {
            try {
                Class<?> utilCls = XposedHelpers.findClassIfExists(clsName, cl);
                if (utilCls == null) continue;
                for (Method m : utilCls.getDeclaredMethods()) {
                    if (m.getReturnType().equals(String.class) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == int.class) {
                        m.setAccessible(true);
                        String desc = (String) m.invoke(null, id);
                        if (desc != null && !desc.trim().isEmpty()) {
                            return desc.replace("/", "");
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static Drawable loadSysFaceDrawable(ClassLoader cl, Context ctx, int id) {
        String[] utilClassNames = {
                "com.tencent.qqnt.emotion.utils.QQSysFaceUtil",
                "com.tencent.mobileqq.emoticon.QQSysFaceUtil"
        };
        for (String clsName : utilClassNames) {
            try {
                Class<?> utilCls = XposedHelpers.findClassIfExists(clsName, cl);
                if (utilCls == null) continue;
                for (Method m : utilCls.getDeclaredMethods()) {
                    if (Drawable.class.isAssignableFrom(m.getReturnType())) {
                        Class<?>[] pts = m.getParameterTypes();
                        m.setAccessible(true);
                        if (pts.length == 1 && pts[0] == int.class) {
                            Object d = m.invoke(null, id);
                            if (d instanceof Drawable) return (Drawable) d;
                        } else if (pts.length == 2 && pts[1] == int.class && ctx != null) {
                            Object d = m.invoke(null, ctx, id);
                            if (d instanceof Drawable) return (Drawable) d;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static synchronized void flushIndexToFile(Context context) {
        try {
            File liveDir = new File(LIVE_EMOJIS_DIR);
            if (!liveDir.exists()) liveDir.mkdirs();

            JSONArray array = new JSONArray();
            for (JSONObject obj : activeIndexMap.values()) {
                if (obj != null) array.put(obj);
            }

            String jsonStr = array.toString();

            // 1. 写出到最新 live_emojis_index.json
            File indexFile = new File(LIVE_INDEX_FILE);
            try (FileOutputStream fos = new FileOutputStream(indexFile)) {
                fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }

            // 2. 兼容写出到旧版 qq_live_faces.json 兜底
            File legacyFile = new File(QQ_LEGACY_DUMP_PATH);
            try (FileOutputStream fos = new FileOutputStream(legacyFile)) {
                fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }

            // 3. 通过 IPC 管道通知模块 App 接收
            if (context != null) {
                try {
                    ContentResolver cr = context.getContentResolver();
                    cr.call(ConfigContentProvider.CONTENT_URI, ConfigContentProvider.METHOD_SAVE_DYNAMIC_FACES, jsonStr, null);
                } catch (Throwable ignored) {
                }
            }

            AppLogger.i(TAG, "已成功刷新动态表情总索引，当前已捕获可用表情总数: " + activeIndexMap.size());

        } catch (Throwable t) {
            AppLogger.e(TAG, "flushIndexToFile 异常", t);
        }
    }

    private static void loadExistingIndex() {
        try {
            File indexFile = new File(LIVE_INDEX_FILE);
            if (!indexFile.exists() || !indexFile.canRead() || indexFile.length() == 0) {
                indexFile = new File(QQ_LEGACY_DUMP_PATH);
            }
            if (indexFile.exists() && indexFile.canRead() && indexFile.length() > 0) {
                try (FileInputStream fis = new FileInputStream(indexFile)) {
                    byte[] buf = new byte[(int) indexFile.length()];
                    int len = fis.read(buf);
                    if (len > 0) {
                        String s = new String(buf, 0, len, StandardCharsets.UTF_8);
                        JSONArray arr = new JSONArray(s);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject obj = arr.getJSONObject(i);
                            String eid = obj.optString("emojiId", String.valueOf(obj.optInt("id")));
                            long etype = obj.optLong("emojiType", 1L);
                            String key = etype + "_" + eid;
                            activeIndexMap.put(key, obj);
                            discoveredKeys.add(key);
                        }
                        AppLogger.i(TAG, "成功从磁盘载入历史动态表情缓存: " + activeIndexMap.size() + " 条");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String findStringOrNumberField(Object target, String... candidateNames) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            for (String name : candidateNames) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val != null) {
                        return String.valueOf(val);
                    }
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) return null;
        try {
            int w = drawable.getIntrinsicWidth() > 0 ? Math.min(drawable.getIntrinsicWidth(), 360) : 128;
            int h = drawable.getIntrinsicHeight() > 0 ? Math.min(drawable.getIntrinsicHeight(), 360) : 128;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, w, h);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void saveBitmapToPng(Bitmap bm, File targetFile) {
        if (bm == null || targetFile == null) return;
        try {
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static Context getHostContext(ClassLoader cl) {
        try {
            Class<?> mobileQQCls = XposedHelpers.findClassIfExists("mqq.app.MobileQQ", cl);
            if (mobileQQCls != null) {
                Object mqq = XposedHelpers.getStaticObjectField(mobileQQCls, "sMobileQQ");
                if (mqq instanceof Context) return (Context) mqq;
                Method getCtx = mobileQQCls.getMethod("getContext");
                return (Context) getCtx.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
