package com.emoji.reactor.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.emoji.reactor.model.DefaultPresets;
import com.emoji.reactor.model.EmojiItem;
import com.emoji.reactor.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双源表情并集融合与私有克隆持久化仓库 (HybridEmojiRepository)
 * 静态全量底库 ∪ QQ 动态实时表情清单 = 100% 极致齐全表情池
 * 具备自动克隆机制：即便用户清理 QQ 缓存，模块内已保存的动态表情永不丢失
 */
public class HybridEmojiRepository {

    private static final String TAG = "QQEmojiReactor_Repo";
    public static final String SHARED_LIVE_INDEX = "/sdcard/Download/QQEmojiReactor/live_emojis/live_emojis_index.json";
    public static final String QQ_LIVE_INDEX = "/sdcard/Android/media/com.tencent.mobileqq/live_emojis/live_emojis_index.json";
    public static final String QQ_LEGACY_INDEX = "/sdcard/Android/media/com.tencent.mobileqq/qq_live_faces.json";
    public static final String DYNAMIC_PREF_NAME = "dynamic_faces_cache";
    public static final String KEY_DYNAMIC_FACES = "faces_json";

    private static volatile List<EmojiItem> mergedSysfacesCache;

    public static synchronized void invalidateCache() {
        mergedSysfacesCache = null;
    }

    /**
     * 获取全量小黄脸表情（静态底库 + QQ 动态热更表情融合，新表情优先置顶）
     */
    public static List<EmojiItem> getMergedSysfaces(Context context) {
        if (mergedSysfacesCache != null && !mergedSysfacesCache.isEmpty()) {
            if (context == null || (!mergedSysfacesCache.get(0).getName().isEmpty())) {
                return mergedSysfacesCache;
            }
        }

        Map<String, EmojiItem> dynamicMap = new LinkedHashMap<>();
        Map<String, EmojiItem> staticMap = new LinkedHashMap<>();

        // 1. 基础源：加载本地 294 个全量小黄脸底库（带官方名称与正版超清图）
        List<EmojiItem> staticBase = DefaultPresets.getAllSupportedSysfaces(context);
        for (EmojiItem item : staticBase) {
            staticMap.put(item.getRawEmojiId(), item);
        }

        // 2. 读取 QQ 动态导出的数据源（优先最新磁盘文件，兜底 SharedPreferences）
        String jsonStr = readStringFromFile(new File(SHARED_LIVE_INDEX));
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            jsonStr = readStringFromFile(new File(QQ_LIVE_INDEX));
        }

        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            if (context != null) {
                try {
                    SharedPreferences sp = context.getSharedPreferences(DYNAMIC_PREF_NAME, Context.MODE_PRIVATE);
                    jsonStr = sp.getString(KEY_DYNAMIC_FACES, null);
                } catch (Throwable ignored) {
                }
            }
        }

        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            jsonStr = readStringFromFile(new File(QQ_LEGACY_INDEX));
        }

        // 3. 解析动态表情列表并克隆图片至模块私有目录
        if (jsonStr != null && !jsonStr.trim().isEmpty()) {
            try {
                JSONArray array = new JSONArray(jsonStr);
                File localFacesDir = context != null ? new File(context.getFilesDir(), "live_faces") : null;
                if (localFacesDir != null && !localFacesDir.exists()) localFacesDir.mkdirs();

                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    String emojiId = obj.optString("emojiId", String.valueOf(obj.optInt("id")));
                    if (emojiId == null || emojiId.trim().isEmpty()) continue;
                    emojiId = emojiId.trim();

                    long emojiType = obj.optLong("emojiType", 1L);
                    String name = obj.optString("name", "");
                    String path = obj.optString("path", null);

                    // 多路径探测有效图片文件
                    String permanentPath = null;
                    if (path != null && new File(path).exists() && new File(path).canRead()) {
                        permanentPath = path;
                    }
                    if (permanentPath == null) {
                        File sharedImg = new File("/sdcard/Download/QQEmojiReactor/live_emojis/face_" + emojiId + ".png");
                        if (sharedImg.exists() && sharedImg.canRead()) {
                            permanentPath = sharedImg.getAbsolutePath();
                        }
                    }
                    if (permanentPath == null && localFacesDir != null) {
                        File privImg = new File(localFacesDir, "face_" + emojiId + ".png");
                        if (privImg.exists() && privImg.canRead()) {
                            permanentPath = privImg.getAbsolutePath();
                        }
                    }

                    // 自动克隆备份图片到模块私有存储
                    if (permanentPath != null && localFacesDir != null) {
                        File srcFile = new File(permanentPath);
                        File destFile = new File(localFacesDir, "face_" + emojiId + ".png");
                        if (!destFile.exists() || destFile.length() != srcFile.length()) {
                            copyFile(srcFile, destFile);
                        }
                        permanentPath = destFile.getAbsolutePath();
                    }

                    EmojiItem staticItem = staticMap.get(emojiId);
                    String finalName = (!name.isEmpty()) ? name : (staticItem != null ? staticItem.getName() : "");

                    int builtInRes = 0;
                    try {
                        builtInRes = DefaultPresets.getEmojiDrawableRes(context, Integer.parseInt(emojiId));
                    } catch (Throwable ignored) {
                    }

                    boolean hasImage = (permanentPath != null && new File(permanentPath).exists());

                    // 无论是有图片还是动态已注册表情，全部准予上屏呈现
                    if (hasImage || builtInRes != 0 || !finalName.isEmpty()) {
                        EmojiItem dynamicItem = new EmojiItem(emojiId, emojiType, finalName, null, permanentPath, true);
                        dynamicMap.put(emojiId, dynamicItem);
                    }
                }
                AppLogger.i(TAG, "已成功从动态源载入并融合 " + dynamicMap.size() + " 款表情！");
            } catch (Throwable t) {
                AppLogger.e(TAG, "解析动态表情 JSON 异常", t);
            }
        }

        // 4. 融合与排序策略：
        // 动态发现的最新表情（新出的 ID）优先前置排布，其余静态底库表情平铺在后，确保 100% 完整收纳无遗漏
        List<EmojiItem> result = new ArrayList<>();

        // ① 先添加非静态底库的全新动态表情（最新款前置）
        for (Map.Entry<String, EmojiItem> entry : dynamicMap.entrySet()) {
            if (!staticMap.containsKey(entry.getKey())) {
                result.add(entry.getValue());
            }
        }

        // ② 再添加底库表情（如果动态源有更新的超清图片路径则智能替换）
        for (Map.Entry<String, EmojiItem> entry : staticMap.entrySet()) {
            String id = entry.getKey();
            if (dynamicMap.containsKey(id) && dynamicMap.get(id).getImagePath() != null) {
                result.add(dynamicMap.get(id));
            } else {
                result.add(entry.getValue());
            }
        }

        mergedSysfacesCache = new ArrayList<>(result);
        return mergedSysfacesCache;
    }

    /**
     * 获取全量 Emoji 表情（1354 款全量）
     */
    public static List<EmojiItem> getAllEmojis(Context context) {
        return DefaultPresets.getAllSupportedEmojis(context);
    }

    private static String readStringFromFile(File file) {
        if (file == null || !file.exists() || !file.canRead() || file.length() < 3) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int len = fis.read(buf);
            if (len > 0) {
                return new String(buf, 0, len, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        return null;
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
}
