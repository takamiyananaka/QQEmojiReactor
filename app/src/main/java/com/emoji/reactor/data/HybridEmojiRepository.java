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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双源表情并集融合与私有克隆持久化仓库 (HybridEmojiRepository)
 * 1. 严格按 QQ 官方内部 ID 升序平铺排布（0 ~ 507）
 * 2. 官方内置 416 款超清正版表情绝对优先，杜绝任何外部错位文件污染
 * 3. 动态发现的全新表情无缝按 ID 插入入库，永不过时
 * 4. 强制上锁 .nomedia，私有沙箱隔离，绝不污染系统相册
 */
public class HybridEmojiRepository {

    private static final String TAG = "QQEmojiReactor_Repo";
    public static final String SAFE_LIVE_INDEX = new File(com.emoji.reactor.util.StoragePaths.getSafeMediaCacheDir(), "live_emojis_index.json").getAbsolutePath();
    public static final String SHARED_LIVE_INDEX = new File(com.emoji.reactor.util.StoragePaths.getLegacyDownloadLiveDir(), "live_emojis_index.json").getAbsolutePath();
    public static final String QQ_LIVE_INDEX = new File(com.emoji.reactor.util.StoragePaths.getLegacyQqMediaDir(), "live_emojis_index.json").getAbsolutePath();
    public static final String QQ_LEGACY_INDEX = com.emoji.reactor.util.StoragePaths.getLegacyQqDumpFile().getAbsolutePath();
    public static final String DYNAMIC_PREF_NAME = "dynamic_faces_cache";
    public static final String KEY_DYNAMIC_FACES = "faces_json";

    private static volatile List<EmojiItem> mergedSysfacesCache;
    private static volatile int dynamicCapturedCount = 0;

    public static synchronized void invalidateCache() {
        mergedSysfacesCache = null;
    }

    public static int getOfficialFaceCount() {
        return DefaultPresets.ALL_OFFICIAL_FACE_IDS.length;
    }

    public static int getDynamicallyCapturedCount(Context context) {
        if (mergedSysfacesCache == null) {
            getMergedSysfaces(context);
        }
        return dynamicCapturedCount;
    }

    /**
     * 获取全量小黄脸表情（严格按 QQ 官方内部 ID 排序，官方超清优先，动态自适应补全）
     */
    public static List<EmojiItem> getMergedSysfaces(Context context) {
        if (mergedSysfacesCache != null && !mergedSysfacesCache.isEmpty()) {
            if (context == null || (!mergedSysfacesCache.get(0).getName().isEmpty())) {
                return mergedSysfacesCache;
            }
        }

        Map<String, EmojiItem> dynamicMap = new LinkedHashMap<>();
        Map<String, EmojiItem> staticMap = new LinkedHashMap<>();

        // 1. 基础源：加载本地 416 个全量小黄脸官方正版底库（带官方名称与正版超清图）
        List<EmojiItem> staticBase = DefaultPresets.getAllSupportedSysfaces(context);
        for (EmojiItem item : staticBase) {
            staticMap.put(item.getRawEmojiId(), item);
        }

        // 2. 读取 QQ 动态导出的数据源（优先安全私有沙箱索引，兜底 Download 与 SharedPreferences）
        String jsonStr = readStringFromFile(new File(SAFE_LIVE_INDEX));
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            jsonStr = readStringFromFile(new File(SHARED_LIVE_INDEX));
        }
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

        // 3. 解析动态表情列表并克隆图片至模块私有沙箱目录
        int newDynamicCount = 0;
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

                    // 如果该 ID 已经在 416 官方静态库中，绝对保留官方原版，不被外部文件替换！
                    if (staticMap.containsKey(emojiId)) {
                        continue;
                    }

                    long emojiType = obj.optLong("emojiType", 1L);
                    String name = obj.optString("name", "");
                    String path = obj.optString("path", null);

                    // 多路径探测有效图片文件
                    String permanentPath = null;
                    if (path != null && new File(path).exists() && new File(path).canRead()) {
                        permanentPath = path;
                    }
                    if (permanentPath == null) {
                        File safeImg = new File(com.emoji.reactor.util.StoragePaths.getSafeMediaCacheDir(), "face_" + emojiId + ".png");
                        if (safeImg.exists() && safeImg.canRead()) {
                            permanentPath = safeImg.getAbsolutePath();
                        }
                    }
                    if (permanentPath == null) {
                        File sharedImg = new File(com.emoji.reactor.util.StoragePaths.getLegacyDownloadLiveDir(), "face_" + emojiId + ".png");
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

                    // 严禁无图幽灵表情上架：必须具备有效且大小 > 0 的真实图片文件！
                    boolean hasValidImage = (permanentPath != null && new File(permanentPath).exists() && new File(permanentPath).length() > 0);
                    if (!hasValidImage) {
                        continue;
                    }

                    String finalName = (!name.isEmpty()) ? name : ("新表情 " + emojiId);
                    EmojiItem dynamicItem = new EmojiItem(emojiId, emojiType, finalName, null, permanentPath, true);
                    dynamicMap.put(emojiId, dynamicItem);
                    newDynamicCount++;
                }
                AppLogger.i(TAG, "已成功从动态源载入并融合 " + dynamicMap.size() + " 款新动态表情！");
            } catch (Throwable t) {
                AppLogger.e(TAG, "解析动态表情 JSON 异常", t);
            }
        }

        dynamicCapturedCount = newDynamicCount;

        // 4. 合并并严格按 QQ 官方内部 ID 升序平铺排序
        List<EmojiItem> result = new ArrayList<>(staticMap.values());
        for (EmojiItem item : dynamicMap.values()) {
            if (!staticMap.containsKey(item.getRawEmojiId())) {
                result.add(item);
            }
        }

        Collections.sort(result, (a, b) -> {
            String idA = a != null ? a.getRawEmojiId() : "";
            String idB = b != null ? b.getRawEmojiId() : "";
            boolean isNumA = isNumeric(idA);
            boolean isNumB = isNumeric(idB);
            if (isNumA && isNumB) {
                try {
                    int numA = Integer.parseInt(idA);
                    int numB = Integer.parseInt(idB);
                    return Integer.compare(numA, numB);
                } catch (NumberFormatException ignored) {
                    return idA.compareTo(idB);
                }
            } else if (isNumA) {
                return -1; // 纯数字小黄脸优先按数值升序排布
            } else if (isNumB) {
                return 1;
            } else {
                return idA.compareTo(idB); // 非纯数字 ID 严格按字典序稳定排布
            }
        });

        mergedSysfacesCache = new ArrayList<>(result);
        return mergedSysfacesCache;
    }

    /**
     * 获取全量 Emoji 表情（1368 款全量，含青蛙 🐸）
     */
    public static List<EmojiItem> getAllEmojis(Context context) {
        return DefaultPresets.getAllSupportedEmojis(context);
    }

    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
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
