package com.emoji.reactor.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.emoji.reactor.data.ConfigContentProvider;
import com.emoji.reactor.data.ConfigManager;
import com.emoji.reactor.model.DefaultPresets;
import com.emoji.reactor.model.EmojiGroup;
import com.emoji.reactor.model.GroupEmojiItem;
import com.emoji.reactor.util.AppLogger;
import com.emoji.reactor.util.StoragePaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 跨进程配置安全读取器（四重持久化穿透版）
 * 无论主 App 是否被系统划掉或处于后台，QQ 进程均能 100% 保持用户自定义方案与开关状态
 */
public class RemoteConfigHelper {

    private static final String TAG = "QQEmojiReactor_Config";
    public static final String PACKAGE_NAME = "com.emoji.reactor";
    private static final String QQ_LOCAL_PREF_NAME = "emoji_reactor_qq_cache";

    public static final String PROVIDER_AUTHORITY = "com.emoji.reactor.provider";
    public static final String METHOD_GET_CONFIG = "get_config";
    public static final String KEY_GROUPS_JSON = "groups_json";
    public static final String KEY_IS_ENABLED = "is_enabled";

    private static android.net.Uri getProviderUri() {
        return android.net.Uri.parse("content://" + PROVIDER_AUTHORITY);
    }

    private static volatile List<EmojiGroup> memoryCachedGroups;
    private static volatile Boolean memoryEnabled = null;
    private static volatile String memoryCustomIconBase64 = null;
    private static final Map<String, Boolean> memoryFeatureMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> memoryCustomAvatarMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean sAvatarsConfigLoaded = false;
    private static volatile XSharedPreferences xsp;

    /**
     * 清除内存缓存，强制下次读取重新从四重穿透层加载（供广播热刷新调用）
     */
    public static synchronized void invalidateCache() {
        memoryCachedGroups = null;
        memoryEnabled = null;
        memoryCustomIconBase64 = null;
        memoryFeatureMap.clear();
        memoryCustomAvatarMap.clear();
        sAvatarsConfigLoaded = false;
        AppLogger.i(TAG, "已重置 QQ 进程内配置内存缓存");
    }

    /**
     * 获取全局 Context (自动兜底)
     */
    public static Context getSafeContext(Context preferred) {
        if (preferred != null) return preferred;
        return MainHook.getAppContext();
    }

    public static String getCustomMenuIconBase64(Context context) {
        if (memoryCustomIconBase64 != null) {
            return memoryCustomIconBase64;
        }
        Context ctx = getSafeContext(context);
        reloadFullConfig(ctx);
        return memoryCustomIconBase64 != null ? memoryCustomIconBase64 : "";
    }

    private static boolean isFileValid(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File f = new File(path);
            return f.exists() && f.length() > 0 && f.canRead();
        } catch (Exception e) {
            AppLogger.d(TAG, "isFileValid 异常安全跳过: " + e.getMessage());
            return false;
        }
    }

    public static String getCustomAvatarPath(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        id = id.trim();

        if (!isModuleEnabled() || !isFeatureEnabled("feature_custom_avatar", true)) {
            return null;
        }

        if (!sAvatarsConfigLoaded) {
            synchronized (RemoteConfigHelper.class) {
                if (!sAvatarsConfigLoaded) {
                    reloadFullConfig(getSafeContext(null));
                    sAvatarsConfigLoaded = true;
                }
            }
        }

        // 1. 直接命中高速缓存 (若已缓存且物理文件有效，瞬时 O(1) 返回)
        String directPath = memoryCustomAvatarMap.get(id);
        if (directPath != null && isFileValid(directPath)) {
            return directPath;
        }

        // 2. 双向映射互查
        String mappedId = id.startsWith("u_") ? UidUinHelper.getUinFromUid(id) : UidUinHelper.getUidFromUin(id);
        if (mappedId != null && !mappedId.isEmpty()) {
            String mappedPath = memoryCustomAvatarMap.get(mappedId);
            if (mappedPath != null && isFileValid(mappedPath)) {
                memoryCustomAvatarMap.put(id, mappedPath);
                return mappedPath;
            }
        }

        // 3. 确定关联的目标 UIN
        String targetUin = id.startsWith("u_") ? mappedId : id;
        if (targetUin == null || targetUin.isEmpty()) {
            targetUin = id;
        }

        // 4. 严格受控过滤：如果既不是受控 UIN，也没有任何配置关联，直接短路保护正常好友与群友
        boolean isConfigured = getAllConfiguredUins().contains(targetUin)
                || memoryCustomAvatarMap.containsKey(targetUin)
                || memoryCustomAvatarMap.containsKey(id);
        if (!isConfigured) {
            return null;
        }

        // 5. 检查 QQ 内部私有目录 (/data/user/0/com.tencent.mobileqq/files/emoji_reactor/custom_avatars/<uin>.png)
        File qqInternal = new File(getQqAvatarDir(null), targetUin + ".png");
        if (qqInternal.exists() && qqInternal.length() > 0 && qqInternal.canRead()) {
            String validPath = qqInternal.getAbsolutePath();
            memoryCustomAvatarMap.put(id, validPath);
            AppLogger.i(TAG, "【命中QQ私有目录头像】ID=" + id + " -> " + validPath);
            return validPath;
        }

        // 6. 检查 QQ 自身媒体目录镜像 (Android/media/com.tencent.mobileqq/emoji_reactor/custom_avatars/<uin>.png)
        File qqMediaAvatar = StoragePaths.getQqAvatarFile(targetUin);
        if (qqMediaAvatar.exists() && qqMediaAvatar.length() > 0 && qqMediaAvatar.canRead()) {
            String validPath = qqMediaAvatar.getAbsolutePath();
            memoryCustomAvatarMap.put(id, validPath);
            AppLogger.i(TAG, "【命中QQ媒体目录头像】ID=" + id + " -> " + validPath);
            return validPath;
        }

        // 7. 检查之前 directPath 是否有效
        if (directPath != null && isFileValid(directPath)) {
            return directPath;
        }

        return null;
    }

    public static File getQqAvatarDir(Context context) {
        Context ctx = getSafeContext(context);
        if (ctx != null) {
            try {
                File dir = new File(ctx.getFilesDir(), "emoji_reactor/custom_avatars");
                if (!dir.exists()) dir.mkdirs();
                return dir;
            } catch (Exception e) {
                AppLogger.d(TAG, "getQqAvatarDir 获取私有目录安全跳过: " + e.getMessage());
            }
        }
        File fallback = new File("/data/data/com.tencent.mobileqq/files/emoji_reactor/custom_avatars");
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    public static File writeAvatarToQqInternal(Context context, String uin, byte[] bytes) {
        if (uin == null || uin.isEmpty() || bytes == null || bytes.length == 0) return null;
        try {
            File dir = getQqAvatarDir(context);
            if (!dir.exists()) dir.mkdirs();
            File target = new File(dir, uin + ".png");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target, false)) {
                fos.write(bytes);
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Exception e) {
                    AppLogger.d(TAG, "writeAvatarToQqInternal sync 安全跳过: " + e.getMessage());
                }
            }
            return target;
        } catch (Throwable t) {
            AppLogger.e(TAG, "writeAvatarToQqInternal 异常: " + uin, t);
            return null;
        }
    }

    public static String fetchAndCacheAvatarFromProvider(Context context, String uin) {
        if (uin == null || uin.isEmpty()) return null;
        Context ctx = getSafeContext(context);
        if (ctx == null) return null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            Bundle b = cr.call(getProviderUri(), ConfigContentProvider.METHOD_GET_AVATAR_BYTES, uin, null);
            if (b != null && b.containsKey(ConfigContentProvider.KEY_AVATAR_BYTES)) {
                byte[] bytes = b.getByteArray(ConfigContentProvider.KEY_AVATAR_BYTES);
                if (bytes != null && bytes.length > 0) {
                    File target = writeAvatarToQqInternal(ctx, uin, bytes);
                    if (target != null && target.exists() && target.length() > 0) {
                        String absPath = target.getAbsolutePath();
                        memoryCustomAvatarMap.put(uin, absPath);
                        AppLogger.i(TAG, "【成功从Provider同步头像到QQ私有目录】UIN=" + uin + " -> " + absPath);
                        return absPath;
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "fetchAndCacheAvatarFromProvider 异常: " + t.getMessage());
        }
        return null;
    }

    public static void linkUidToUin(String uid, String uin) {
        if (uid == null || uin == null) return;
        uid = uid.trim();
        uin = uin.trim();
        String path = memoryCustomAvatarMap.get(uin);
        if (path != null) {
            memoryCustomAvatarMap.put(uid, path);
        }
    }

    public static java.util.Set<String> getAllConfiguredUins() {
        if (!sAvatarsConfigLoaded) {
            synchronized (RemoteConfigHelper.class) {
                if (!sAvatarsConfigLoaded) {
                    reloadFullConfig(getSafeContext(null));
                    sAvatarsConfigLoaded = true;
                }
            }
        }
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String k : memoryCustomAvatarMap.keySet()) {
            if (k != null && !k.isEmpty() && !k.startsWith("u_")) {
                set.add(k);
            }
        }
        return set;
    }

    public static java.util.Set<String> getAllCustomAvatarUins() {
        if (!sAvatarsConfigLoaded) {
            synchronized (RemoteConfigHelper.class) {
                if (!sAvatarsConfigLoaded) {
                    reloadFullConfig(getSafeContext(null));
                    sAvatarsConfigLoaded = true;
                }
            }
        }
        return new java.util.HashSet<>(memoryCustomAvatarMap.keySet());
    }

    public static boolean isModuleEnabled() {
        return isModuleEnabled(null);
    }

    public static boolean isModuleEnabled(Context context) {
        if (memoryEnabled != null) {
            return memoryEnabled;
        }
        Context ctx = getSafeContext(context);
        reloadFullConfig(ctx);
        if (memoryEnabled != null) {
            return memoryEnabled;
        }
        memoryEnabled = true;
        return true;
    }

    public static boolean isFeatureEnabled(String key, boolean defaultVal) {
        return isFeatureEnabled(null, key, defaultVal);
    }

    public static boolean isFeatureEnabled(Context context, String key, boolean defaultVal) {
        if (key == null) return defaultVal;
        Boolean cached = memoryFeatureMap.get(key);
        if (cached != null) {
            return cached;
        }
        Context ctx = getSafeContext(context);
        reloadFullConfig(ctx);
        Boolean res = memoryFeatureMap.get(key);
        if (res != null) {
            return res;
        }
        memoryFeatureMap.put(key, defaultVal);
        return defaultVal;
    }

    /**
     * 获取用户配置的全部表情方案（多重安全穿透保障，永久保留用户自定义方案）
     */
    public static List<EmojiGroup> getGroups(Context context) {
        if (memoryCachedGroups != null && !memoryCachedGroups.isEmpty()) {
            return memoryCachedGroups;
        }
        Context ctx = getSafeContext(context);
        reloadFullConfig(ctx);
        if (memoryCachedGroups != null && !memoryCachedGroups.isEmpty()) {
            return memoryCachedGroups;
        }
        return DefaultPresets.getDefaultGroups();
    }

    /**
     * 四重穿透核心：全量配置重载并写入 QQ 本地缓存镜像
     */
    public static synchronized void reloadFullConfig(Context context) {
        boolean loaded = false;
        Context ctx = getSafeContext(context);

        // 通道 1：从 ContentProvider 拉取（App 活跃时实时穿透）
        if (ctx != null) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                Bundle bundle = cr.call(getProviderUri(), METHOD_GET_CONFIG, null, null);
                if (bundle != null) {
                    if (bundle.containsKey(KEY_IS_ENABLED)) {
                        boolean en = bundle.getBoolean(KEY_IS_ENABLED, true);
                        memoryEnabled = en;
                    }
                    if (bundle.containsKey(ConfigContentProvider.KEY_CUSTOM_ICON_BASE64)) {
                        memoryCustomIconBase64 = bundle.getString(ConfigContentProvider.KEY_CUSTOM_ICON_BASE64, "");
                    }
                    for (String k : bundle.keySet()) {
                        if (k.startsWith("feature_")) {
                            memoryFeatureMap.put(k, bundle.getBoolean(k, true));
                        }
                    }
                    String str = bundle.getString(KEY_GROUPS_JSON, null);
                    if (str != null && !str.trim().isEmpty() && !str.equals("[]")) {
                        List<EmojiGroup> groups = parseGroupsJson(str);
                        if (!groups.isEmpty()) {
                            memoryCachedGroups = groups;
                        }
                    }
                    String avatarsStr = bundle.getString(ConfigContentProvider.KEY_CUSTOM_AVATARS_JSON, null);
                    if (avatarsStr != null && !avatarsStr.trim().isEmpty()) {
                        parseCustomAvatarsJson(avatarsStr);
                        // 遍历 bundle 附带的头像 bytes 并直接写入 QQ 私有目录
                        try {
                            JSONArray aArr = new JSONArray(avatarsStr);
                            for (int i = 0; i < aArr.length(); i++) {
                                JSONObject aObj = aArr.getJSONObject(i);
                                String uin = aObj.optString("uin", "").trim();
                                if (!uin.isEmpty() && aObj.optBoolean("enabled", true)) {
                                    byte[] bytes = bundle.getByteArray("avatar_bytes_" + uin);
                                    if (bytes != null && bytes.length > 0) {
                                        File qqF = writeAvatarToQqInternal(ctx, uin, bytes);
                                        if (qqF != null && qqF.exists()) {
                                            memoryCustomAvatarMap.put(uin, qqF.getAbsolutePath());
                                            AppLogger.i(TAG, "【Bundle穿透并写入QQ私有目录】UIN=" + uin + " -> " + qqF.getAbsolutePath());
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            AppLogger.d(TAG, "解析Bundle穿透头像安全跳过: " + e.getMessage());
                        }
                        UidUinHelper.warmUpConfiguredUins();
                    }
                    cacheFullToQqStorage(ctx);
                    loaded = true;
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "Provider 通道 1 失败安全跳过: " + e.getMessage());
            }
        }

        // 通道 2 & 3：从系统公共共享目录或媒体目录文件读取 (config.json)
        if (!loaded) {
            String fileJson = readStringFromFile(StoragePaths.getQqMediaConfigFile());
            if (fileJson == null || fileJson.trim().isEmpty()) {
                fileJson = readStringFromFile(StoragePaths.getSharedConfigFile());
            }
            if (fileJson == null || fileJson.trim().isEmpty()) {
                fileJson = readStringFromFile(StoragePaths.getSafeMediaConfigFile());
            }

            if (fileJson != null && !fileJson.trim().isEmpty()) {
                try {
                    if (fileJson.trim().startsWith("{")) {
                        JSONObject jsonObject = new JSONObject(fileJson);
                        if (jsonObject.has(ConfigManager.KEY_ENABLED)) {
                            memoryEnabled = jsonObject.optBoolean(ConfigManager.KEY_ENABLED, true);
                        }
                        if (jsonObject.has(ConfigManager.KEY_CUSTOM_ICON)) {
                            memoryCustomIconBase64 = jsonObject.optString(ConfigManager.KEY_CUSTOM_ICON, "");
                        }
                        Iterator<String> keys = jsonObject.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            if (k.startsWith("feature_")) {
                                memoryFeatureMap.put(k, jsonObject.optBoolean(k, true));
                            }
                        }
                        if (jsonObject.has(ConfigManager.KEY_GROUPS)) {
                            JSONArray gArr = jsonObject.optJSONArray(ConfigManager.KEY_GROUPS);
                            if (gArr != null) {
                                List<EmojiGroup> groups = parseGroupsJson(gArr.toString());
                                if (!groups.isEmpty()) {
                                    memoryCachedGroups = groups;
                                }
                            }
                        }
                        if (jsonObject.has(ConfigManager.KEY_CUSTOM_AVATARS)) {
                            JSONArray aArr = jsonObject.optJSONArray(ConfigManager.KEY_CUSTOM_AVATARS);
                            if (aArr != null) {
                                parseCustomAvatarsJson(aArr.toString());
                            }
                        }
                    } else if (fileJson.trim().startsWith("[")) {
                        List<EmojiGroup> groups = parseGroupsJson(fileJson);
                        if (!groups.isEmpty()) {
                            memoryCachedGroups = groups;
                        }
                    }
                    if (ctx != null) cacheFullToQqStorage(ctx);
                    loaded = true;
                } catch (Exception e) {
                    AppLogger.d(TAG, "文件通道 2/3 解析安全跳过: " + e.getMessage());
                }
            }
        }

        // 通道 4：从 QQ 自身的私有 SharedPreferences 镜像中读取（即使主 App 被彻底划掉也永久生效！）
        if (!loaded && ctx != null) {
            try {
                SharedPreferences qqSp = ctx.getSharedPreferences(QQ_LOCAL_PREF_NAME, Context.MODE_PRIVATE);
                if (qqSp.contains(ConfigManager.KEY_ENABLED)) {
                    memoryEnabled = qqSp.getBoolean(ConfigManager.KEY_ENABLED, true);
                }
                if (qqSp.contains(ConfigManager.KEY_CUSTOM_ICON)) {
                    memoryCustomIconBase64 = qqSp.getString(ConfigManager.KEY_CUSTOM_ICON, "");
                }
                for (Map.Entry<String, ?> entry : qqSp.getAll().entrySet()) {
                    if (entry.getKey().startsWith("feature_") && entry.getValue() instanceof Boolean) {
                        memoryFeatureMap.put(entry.getKey(), (Boolean) entry.getValue());
                    }
                }
                String str = qqSp.getString(ConfigManager.KEY_GROUPS, null);
                if (str != null && !str.trim().isEmpty()) {
                    List<EmojiGroup> groups = parseGroupsJson(str);
                    if (!groups.isEmpty()) {
                        memoryCachedGroups = groups;
                    }
                }
                String aStr = qqSp.getString(ConfigManager.KEY_CUSTOM_AVATARS, null);
                if (aStr != null && !aStr.trim().isEmpty()) {
                    parseCustomAvatarsJson(aStr);
                    UidUinHelper.warmUpConfiguredUins();
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "QQ 本地 SP 镜像读取安全跳过: " + e.getMessage());
            }
        }

        // 通道 5：从 XSharedPreferences 兜底
        if (!loaded) {
            try {
                if (xsp == null) {
                    xsp = new XSharedPreferences(PACKAGE_NAME, ConfigManager.PREF_NAME);
                } else {
                    xsp.reload();
                }
                if (memoryEnabled == null) {
                    memoryEnabled = xsp.getBoolean(ConfigManager.KEY_ENABLED, true);
                }
                if (memoryCustomIconBase64 == null) {
                    memoryCustomIconBase64 = xsp.getString(ConfigManager.KEY_CUSTOM_ICON, "");
                }
                for (Map.Entry<String, ?> entry : xsp.getAll().entrySet()) {
                    if (entry.getKey().startsWith("feature_") && entry.getValue() instanceof Boolean) {
                        memoryFeatureMap.put(entry.getKey(), (Boolean) entry.getValue());
                    }
                }
                if (memoryCachedGroups == null) {
                    String str = xsp.getString(ConfigManager.KEY_GROUPS, null);
                    if (str != null && !str.trim().isEmpty()) {
                        List<EmojiGroup> groups = parseGroupsJson(str);
                        if (!groups.isEmpty()) {
                            memoryCachedGroups = groups;
                        }
                    }
                }
                String aStr = xsp.getString(ConfigManager.KEY_CUSTOM_AVATARS, null);
                if (aStr != null && !aStr.trim().isEmpty()) {
                    parseCustomAvatarsJson(aStr);
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "XSharedPreferences 通道 5 读取安全跳过: " + e.getMessage());
            }
        }
    }

    private static String readStringFromFile(File file) {
        if (file == null || !file.exists() || file.length() < 2) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int len = fis.read(buf);
            if (len > 0) {
                return new String(buf, 0, len, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            AppLogger.d(TAG, "readStringFromFile 无法直读 " + file.getAbsolutePath() + ": " + t.getMessage());
        }
        return null;
    }

    private static void cacheFullToQqStorage(Context context) {
        if (context == null) return;
        try {
            SharedPreferences qqSp = context.getSharedPreferences(QQ_LOCAL_PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = qqSp.edit();
            if (memoryEnabled != null) {
                editor.putBoolean(ConfigManager.KEY_ENABLED, memoryEnabled);
            }
            if (memoryCustomIconBase64 != null) {
                editor.putString(ConfigManager.KEY_CUSTOM_ICON, memoryCustomIconBase64);
            }
            for (Map.Entry<String, Boolean> entry : memoryFeatureMap.entrySet()) {
                editor.putBoolean(entry.getKey(), entry.getValue());
            }
            if (memoryCachedGroups != null && !memoryCachedGroups.isEmpty()) {
                editor.putString(ConfigManager.KEY_GROUPS, groupsToJsonString(memoryCachedGroups));
            }
            editor.putString(ConfigManager.KEY_CUSTOM_AVATARS, customAvatarsToJsonString());
            editor.apply();
        } catch (Exception e) {
            AppLogger.d(TAG, "cacheFullToQqStorage 安全跳过: " + e.getMessage());
        }
    }

    public static String groupsToJsonString(List<EmojiGroup> groups) {
        if (groups == null) return "[]";
        try {
            JSONArray array = new JSONArray();
            for (EmojiGroup g : groups) {
                if (g == null) continue;
                JSONObject obj = new JSONObject();
                obj.put("id", g.getId());
                obj.put("name", g.getName());
                obj.put("delay", g.getDelayMs());
                JSONArray items = new JSONArray();
                for (GroupEmojiItem item : g.getItems()) {
                    if (item == null) continue;
                    JSONObject it = new JSONObject();
                    it.put("emojiId", item.getEmojiId());
                    it.put("emojiType", item.getEmojiType());
                    items.put(it);
                }
                obj.put("emojis", items);
                array.put(obj);
            }
            return array.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    /**
     * 通用自适应方案解析器（同时支持最新 GroupEmojiItem 对象与旧版数字 ID）
     */
    public static List<EmojiGroup> parseGroupsJson(String jsonStr) {
        List<EmojiGroup> list = new ArrayList<>();
        if (jsonStr == null || jsonStr.trim().isEmpty()) return list;

        try {
            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String id = obj.optString("id");
                String name = obj.optString("name", "方案" + (i + 1));
                int delay = obj.optInt("delay", 100);

                List<GroupEmojiItem> items = new ArrayList<>();
                JSONArray emojisArr = obj.optJSONArray("emojis");
                if (emojisArr != null) {
                    for (int j = 0; j < emojisArr.length(); j++) {
                        Object elem = emojisArr.get(j);
                        if (elem instanceof JSONObject) {
                            JSONObject itemObj = (JSONObject) elem;
                            String emojiId = itemObj.optString("emojiId", "");
                            long emojiType = itemObj.optLong("emojiType", 1L);
                            items.add(new GroupEmojiItem(emojiId, emojiType));
                        } else if (elem instanceof Number) {
                            int legacyId = ((Number) elem).intValue();
                            items.add(new GroupEmojiItem(legacyId));
                        } else if (elem instanceof String) {
                            String strVal = (String) elem;
                            try {
                                int legacyId = Integer.parseInt(strVal);
                                items.add(new GroupEmojiItem(legacyId));
                            } catch (NumberFormatException e) {
                                items.add(new GroupEmojiItem(strVal, 1L));
                            }
                        }
                    }
                }
                list.add(EmojiGroup.fromItems(id, name, items, delay));
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "解析配置 JSON 异常", t);
        }
        return list;
    }

    public static String customAvatarsToJsonString() {
        try {
            JSONArray array = new JSONArray();
            synchronized (memoryCustomAvatarMap) {
                for (Map.Entry<String, String> entry : memoryCustomAvatarMap.entrySet()) {
                    if (entry.getKey() != null && !entry.getKey().isEmpty() && entry.getValue() != null) {
                        JSONObject obj = new JSONObject();
                        obj.put("uin", entry.getKey());
                        obj.put("enabled", true);
                        obj.put("imagePath", entry.getValue());
                        array.put(obj);
                    }
                }
            }
            return array.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    public static boolean parseCustomAvatarsJson(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) return false;
        jsonStr = jsonStr.trim();
        if (jsonStr.startsWith("'") && jsonStr.endsWith("'")) {
            jsonStr = jsonStr.substring(1, jsonStr.length() - 1).trim();
        }
        try {
            JSONArray arr = new JSONArray(jsonStr);
            java.util.Set<String> incomingActiveUins = new java.util.HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String uin = obj.optString("uin", "").trim();
                boolean enabled = obj.optBoolean("enabled", true);
                String imagePath = obj.optString("imagePath", "");
                String base64 = obj.optString("imageBase64", "");
                long lastModified = obj.optLong("lastModified", System.currentTimeMillis());

                if (!uin.isEmpty() && enabled) {
                    incomingActiveUins.add(uin);
                    if (!base64.isEmpty()) {
                        try {
                            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                            File qqFile = writeAvatarToQqInternal(null, uin, bytes);
                            if (qqFile != null && qqFile.exists()) {
                                qqFile.setLastModified(lastModified);
                                memoryCustomAvatarMap.put(uin, qqFile.getAbsolutePath());
                                AppLogger.i(TAG, "【Base64直写QQ私有目录成功】UIN=" + uin + " -> " + qqFile.getAbsolutePath() + " (ts=" + lastModified + ")");
                                continue;
                            }
                        } catch (Throwable t) {
                            AppLogger.e(TAG, "Base64 解码写入私有目录异常: " + uin, t);
                        }
                    }
                    if (!imagePath.isEmpty()) {
                        memoryCustomAvatarMap.put(uin, imagePath);
                        AppLogger.i(TAG, "成功载入自定义头像配置: UIN=" + uin + " -> " + imagePath);
                    }
                }
            }

            // 物理遍历并彻底清理 QQ 内部私有目录中已被删除或禁用的孤儿头像文件！
            File qqInternalDir = getQqAvatarDir(null);
            if (qqInternalDir.exists() && qqInternalDir.isDirectory()) {
                File[] orphanFiles = qqInternalDir.listFiles();
                if (orphanFiles != null) {
                    for (File of : orphanFiles) {
                        String name = of.getName();
                        if (name.endsWith(".png")) {
                            String u = name.substring(0, name.length() - 4);
                            if (!incomingActiveUins.contains(u)) {
                                of.delete();
                                AppLogger.i(TAG, "【物理删除已停用QQ私有头像】" + of.getAbsolutePath());
                            }
                        }
                    }
                }
            }

            // 清理 QQ 媒体目录孤儿头像文件
            File qqMediaAvatarsDir = StoragePaths.getQqMediaAvatarsDir();
            if (qqMediaAvatarsDir.exists() && qqMediaAvatarsDir.isDirectory()) {
                File[] orphanFiles = qqMediaAvatarsDir.listFiles();
                if (orphanFiles != null) {
                    for (File of : orphanFiles) {
                        String name = of.getName();
                        if (name.endsWith(".png")) {
                            String u = name.substring(0, name.length() - 4);
                            if (!incomingActiveUins.contains(u)) {
                                of.delete();
                            }
                        }
                    }
                }
            }

            // 内存映射表全量 Diff 清理
            for (String cachedKey : new java.util.HashSet<>(memoryCustomAvatarMap.keySet())) {
                if (cachedKey == null || cachedKey.isEmpty()) continue;
                String checkUin = cachedKey.startsWith("u_") ? UidUinHelper.getUinFromUid(cachedKey) : cachedKey;
                if (checkUin != null && !incomingActiveUins.contains(checkUin)) {
                    memoryCustomAvatarMap.remove(cachedKey);
                }
            }

            sAvatarsConfigLoaded = true;
            UidUinHelper.warmUpConfiguredUins();
            try {
                Context safeCtx = getSafeContext(null);
                if (safeCtx != null) {
                    cacheFullToQqStorage(safeCtx);
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "parseCustomAvatarsJson cacheFull 安全跳过: " + e.getMessage());
            }
            return true;
        } catch (Throwable t) {
            AppLogger.e(TAG, "parseCustomAvatarsJson 异常: " + t.getMessage(), t);
            return false;
        }
    }
}
