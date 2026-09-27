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
import com.emoji.reactor.model.DefaultPresets;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * QQ 运行时动态表情自适应捕获引擎 (QQDynamicEmojiDumper)
 * 1. 彻底移除了开机主动反射调用（杜绝 Native 字体引擎 VasFont 崩溃闪退，全版本稳定兼容）
 * 2. 挂载贴表情实时数据流嗅探探针（emojiLikesList），只要群聊有人贴出新表情秒级捕获
 * 3. 强制加入 .nomedia 锁，并隔离在私有沙箱中，彻底杜绝手机相册刷屏
 */
public class QQDynamicEmojiDumper {

    private static final String TAG = "QQEmojiReactor_Dumper";
    public static final String LIVE_EMOJIS_DIR = com.emoji.reactor.util.StoragePaths.getSafeMediaCacheDir().getAbsolutePath();
    public static final String LIVE_INDEX_FILE = new File(com.emoji.reactor.util.StoragePaths.getSafeMediaCacheDir(), "live_emojis_index.json").getAbsolutePath();
    public static final String LEGACY_DOWNLOAD_DIR = com.emoji.reactor.util.StoragePaths.getLegacyDownloadLiveDir().getAbsolutePath();
    public static final String LEGACY_DOWNLOAD_INDEX = new File(com.emoji.reactor.util.StoragePaths.getLegacyDownloadLiveDir(), "live_emojis_index.json").getAbsolutePath();
    public static final String QQ_LEGACY_DUMP_PATH = com.emoji.reactor.util.StoragePaths.getLegacyQqDumpFile().getAbsolutePath();
    public static final String QQ_LEGACY_MEDIA_DIR = com.emoji.reactor.util.StoragePaths.getLegacyQqMediaDir().getAbsolutePath();

    private static HandlerThread dumpThread;
    private static Handler dumpHandler;
    private static final Set<String> discoveredKeys = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, JSONObject> activeIndexMap = new ConcurrentHashMap<>();
    private static final Set<Integer> builtInFaceIdSet = new HashSet<>();

    static {
        for (int id : DefaultPresets.ALL_OFFICIAL_FACE_IDS) {
            builtInFaceIdSet.add(id);
        }
    }

    private static volatile ClassLoader hostClassLoader;

    public static synchronized void init(ClassLoader cl) {
        if (cl == null) return;
        hostClassLoader = cl;

        if (dumpThread == null) {
            dumpThread = new HandlerThread("EmojiDumper-Thread");
            dumpThread.start();
            dumpHandler = new Handler(dumpThread.getLooper());
        }

        // 1. 确保所有相关目录上锁 .nomedia，从底层彻底阻止 Android 相册扫描
        ensureNomediaLocks();

        // 2. 初始化加载磁盘已有动态索引
        loadExistingIndex();

        // 3. 挂载被动安全监听探针（零主动反射轰炸，绝不引起 QQ 闪退）
        attachRealtimeSniffers(cl);
    }

    /**
     * 在模块表情文件夹内强制创建 .nomedia 文件，彻底阻断安卓媒体扫描器刷屏
     */
    public static void ensureNomediaLocks() {
        String[] dirs = {
                LIVE_EMOJIS_DIR,
                LEGACY_DOWNLOAD_DIR,
                QQ_LEGACY_MEDIA_DIR,
                com.emoji.reactor.util.StoragePaths.getSharedDownloadDir().getAbsolutePath()
        };
        for (String d : dirs) {
            try {
                File dirFile = new File(d);
                if (dirFile.exists() || dirFile.mkdirs()) {
                    File nomedia = new File(dirFile, ".nomedia");
                    if (!nomedia.exists()) {
                        nomedia.createNewFile();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 实时被动消息气泡与贴表情数据流嗅探器
     */
    private static void attachRealtimeSniffers(ClassLoader cl) {
        // 嗅探 A：Hook AniStickerLottieView 与动画表情渲染组件
        try {
            Class<?> lottieViewClass = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.aio.anisticker.view.AniStickerLottieView", cl);
            if (lottieViewClass != null) {
                for (Method m : lottieViewClass.getDeclaredMethods()) {
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
                AppLogger.i(TAG, "已挂载 AniStickerLottieView 实时嗅探探针");
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "挂载 AniStickerLottieView 跳过: " + t.getMessage());
        }

        // 嗅探 B：Hook AIO 消息项绑定过程，提取实时展示的 MsgRecord
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
            AppLogger.d(TAG, "挂载 BaseContentComponent 探针跳过: " + t.getMessage());
        }
    }

    private static void handleAniStickerMethodInvoked(XC_MethodHook.MethodHookParam param) {
        if (param == null || param.args == null) return;
        for (Object arg : param.args) {
            if (arg == null) continue;
            try {
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

    /**
     * 核心嗅探器：全量解析 MsgRecord 中的贴表情 (emojiLikesList) 与 气泡元素 (elements)
     */
    public static void sniffMsgRecordElements(Object msgRecord) {
        if (msgRecord == null) return;
        try {
            // 1. 嗅探贴表情 (MsgEmojiLikes) - 最关键的数据源！
            List<?> emojiLikes = null;
            try {
                emojiLikes = (List<?>) XposedHelpers.getObjectField(msgRecord, "emojiLikesList");
            } catch (Throwable ignored) {
                try {
                    emojiLikes = (List<?>) XposedHelpers.callMethod(msgRecord, "getEmojiLikesList");
                } catch (Throwable ignored2) {
                }
            }
            if (emojiLikes != null && !emojiLikes.isEmpty()) {
                for (Object likeItem : emojiLikes) {
                    if (likeItem == null) continue;
                    try {
                        String emojiId = (String) XposedHelpers.getObjectField(likeItem, "emojiId");
                        long emojiType = XposedHelpers.getLongField(likeItem, "emojiType");
                        if (emojiId != null && !emojiId.trim().isEmpty()) {
                            onEmojiDiscovered(emojiId.trim(), emojiType, null, null, null, null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 2. 嗅探文本气泡内的 faceElement (如直接输入的 [微笑] 等)
            List<?> elements = null;
            try {
                elements = (List<?>) XposedHelpers.getObjectField(msgRecord, "elements");
            } catch (Throwable ignored) {
            }
            if (elements != null && !elements.isEmpty()) {
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
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 发现并自适应记录一个新表情
     */
    public static void onEmojiDiscovered(String emojiId, long emojiType, String name, String sourcePath, Drawable optionalDrawable, Context context) {
        if (emojiId == null || emojiId.trim().isEmpty()) return;
        final String cleanId = emojiId.trim();
        final String key = emojiType + "_" + cleanId;

        if (discoveredKeys.contains(key) && activeIndexMap.containsKey(key)) {
            return;
        }

        // 如果是已内置的 416 款官方小黄脸，完全不需要额外生成图片或污染存储，直接标记已存在
        if (emojiType == 1L) {
            try {
                int idNum = Integer.parseInt(cleanId);
                if (builtInFaceIdSet.contains(idNum)) {
                    discoveredKeys.add(key);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        if (dumpHandler != null) {
            dumpHandler.post(() -> {
                try {
                    File liveDir = new File(LIVE_EMOJIS_DIR);
                    if (!liveDir.exists()) liveDir.mkdirs();
                    ensureNomediaLocks();

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

                    // 2. 如果缺少图片且是小黄脸，安全通过 localId 转换生成
                    if (finalPath == null && emojiType == 1L && hostClassLoader != null) {
                        try {
                            int fid = Integer.parseInt(cleanId);
                            int localId = convertServerToLocal(hostClassLoader, fid);
                            Drawable d = loadSysFaceDrawableByLocalId(hostClassLoader, context, localId);
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

                    // 3. 提取名称
                    String finalName = name != null ? name.trim() : "";
                    if (finalName.isEmpty() && emojiType == 1L && hostClassLoader != null) {
                        try {
                            int fid = Integer.parseInt(cleanId);
                            int localId = convertServerToLocal(hostClassLoader, fid);
                            finalName = loadSysFaceDescriptionByLocalId(hostClassLoader, localId);
                        } catch (Throwable ignored) {
                        }
                    }

                    // 严禁无图幽灵小黄脸：必须具备有效图片文件，绝不上报空壳！
                    if (emojiType == 1L && finalPath == null) {
                        return;
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

                    // 写出索引文件并通知
                    flushIndexToFile(context != null ? context : getHostContext(hostClassLoader));

                } catch (Throwable t) {
                    AppLogger.e(TAG, "onEmojiDiscovered 处理异常", t);
                }
            });
        }
    }

    private static int convertServerToLocal(ClassLoader cl, int serverId) {
        String[] utilClassNames = {
                "com.tencent.mobileqq.emoticon.QQSysFaceUtil",
                "com.tencent.qqnt.emotion.utils.QQSysFaceUtil"
        };
        for (String clsName : utilClassNames) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(clsName, cl);
                if (cls != null) {
                    Method m = cls.getMethod("convertToLocal", int.class);
                    return (int) m.invoke(null, serverId);
                }
            } catch (Throwable ignored) {
            }
        }
        return serverId;
    }

    private static String loadSysFaceDescriptionByLocalId(ClassLoader cl, int localId) {
        String[] utilClassNames = {
                "com.tencent.mobileqq.emoticon.QQSysFaceUtil",
                "com.tencent.qqnt.emotion.utils.QQSysFaceUtil"
        };
        for (String clsName : utilClassNames) {
            try {
                Class<?> utilCls = XposedHelpers.findClassIfExists(clsName, cl);
                if (utilCls == null) continue;
                Method m = utilCls.getMethod("getFaceDescription", int.class);
                String desc = (String) m.invoke(null, localId);
                if (desc != null && !desc.trim().isEmpty()) {
                    return desc.replace("/", "");
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static Drawable loadSysFaceDrawableByLocalId(ClassLoader cl, Context ctx, int localId) {
        String[] utilClassNames = {
                "com.tencent.mobileqq.emoticon.QQSysFaceUtil",
                "com.tencent.qqnt.emotion.utils.QQSysFaceUtil"
        };
        for (String clsName : utilClassNames) {
            try {
                Class<?> utilCls = XposedHelpers.findClassIfExists(clsName, cl);
                if (utilCls == null) continue;
                Method m = utilCls.getMethod("getFaceDrawable", int.class);
                Object d = m.invoke(null, localId);
                if (d instanceof Drawable) return (Drawable) d;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static synchronized void flushIndexToFile(Context context) {
        try {
            File liveDir = new File(LIVE_EMOJIS_DIR);
            if (!liveDir.exists()) liveDir.mkdirs();
            ensureNomediaLocks();

            JSONArray array = new JSONArray();
            for (JSONObject obj : activeIndexMap.values()) {
                if (obj != null) array.put(obj);
            }

            String jsonStr = array.toString();

            // 1. 写出到最新沙箱 live_emojis_index.json
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
                indexFile = new File(LEGACY_DOWNLOAD_INDEX);
            }
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
