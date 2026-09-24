package com.emoji.reactor.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.emoji.reactor.data.ConfigManager;
import com.emoji.reactor.model.DefaultPresets;
import com.emoji.reactor.model.EmojiGroup;
import com.emoji.reactor.model.GroupEmojiItem;
import com.emoji.reactor.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 跨进程配置安全读取器（四重持久化穿透版）
 * 无论主 App 是否被系统划掉或处于后台，QQ 进程均能 100% 保持用户自定义方案，绝不回退
 */
public class RemoteConfigHelper {

    private static final String TAG = "QQEmojiReactor_Config";
    public static final String PACKAGE_NAME = "com.emoji.reactor";
    private static final String QQ_LOCAL_PREF_NAME = "emoji_reactor_qq_cache";
    public static final String SHARED_CONFIG_PATH = "/sdcard/Download/QQEmojiReactor/config.json";
    public static final String LEGACY_MEDIA_CONFIG_PATH = "/sdcard/Android/media/com.emoji.reactor/config.json";

    public static final String PROVIDER_AUTHORITY = "com.emoji.reactor.provider";
    public static final String METHOD_GET_CONFIG = "get_config";
    public static final String KEY_GROUPS_JSON = "groups_json";
    public static final String KEY_IS_ENABLED = "is_enabled";

    private static android.net.Uri getProviderUri() {
        return android.net.Uri.parse("content://" + PROVIDER_AUTHORITY);
    }

    private static volatile List<EmojiGroup> memoryCachedGroups;
    private static volatile Boolean memoryEnabled;
    private static XSharedPreferences xsp;

    /**
     * 判断模块是否处于开启状态
     */
    public static boolean isEnabled(Context context) {
        if (context != null) {
            try {
                ContentResolver cr = context.getContentResolver();
                Bundle bundle = cr.call(getProviderUri(), METHOD_GET_CONFIG, null, null);
                if (bundle != null && bundle.containsKey(KEY_IS_ENABLED)) {
                    boolean enabled = bundle.getBoolean(KEY_IS_ENABLED, true);
                    memoryEnabled = enabled;
                    return enabled;
                }
            } catch (Throwable ignored) {
            }
        }

        if (memoryEnabled != null) {
            return memoryEnabled;
        }

        try {
            if (xsp == null) {
                xsp = new XSharedPreferences(PACKAGE_NAME, ConfigManager.PREF_NAME);
                xsp.makeWorldReadable();
            } else {
                xsp.reload();
            }
            return xsp.getBoolean(ConfigManager.KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 获取用户配置的全部表情方案（多重安全穿透保障，永久保留用户自定义方案）
     */
    public static List<EmojiGroup> getGroups(Context context) {
        String jsonStr = null;

        // 通道 1：从 ContentProvider 拉取（App 活跃时实时更新）
        if (context != null) {
            try {
                ContentResolver cr = context.getContentResolver();
                Bundle bundle = cr.call(getProviderUri(), METHOD_GET_CONFIG, null, null);
                if (bundle != null) {
                    String str = bundle.getString(KEY_GROUPS_JSON, null);
                    if (str != null && !str.trim().isEmpty() && !str.equals("[]")) {
                        jsonStr = str;
                        cacheToQqStorage(context, str);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 通道 2：从系统级公共共享目录读取（/sdcard/Download/QQEmojiReactor/config.json，最可靠互通通道）
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            jsonStr = readStringFromFile(new File(SHARED_CONFIG_PATH));
            if (jsonStr != null && context != null) cacheToQqStorage(context, jsonStr);
        }

        // 通道 3：从外部公开媒体目录镜像文件读取（/sdcard/Android/media/com.emoji.reactor/config.json）
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            jsonStr = readStringFromFile(new File(LEGACY_MEDIA_CONFIG_PATH));
            if (jsonStr != null && context != null) cacheToQqStorage(context, jsonStr);
        }

        // 通道 4：从 XSharedPreferences 兜底
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            try {
                if (xsp == null) {
                    xsp = new XSharedPreferences(PACKAGE_NAME, ConfigManager.PREF_NAME);
                    xsp.makeWorldReadable();
                } else {
                    xsp.reload();
                }
                String str = xsp.getString(ConfigManager.KEY_GROUPS, null);
                if (str != null && !str.trim().isEmpty()) {
                    jsonStr = str;
                    if (context != null) cacheToQqStorage(context, str);
                }
            } catch (Throwable ignored) {
            }
        }

        // 通道 5：从 QQ 自身的私有 SharedPreferences 镜像中读取（即使 App 彻底被杀死也永久有效！）
        if ((jsonStr == null || jsonStr.trim().isEmpty()) && context != null) {
            try {
                SharedPreferences qqSp = context.getSharedPreferences(QQ_LOCAL_PREF_NAME, Context.MODE_PRIVATE);
                String str = qqSp.getString(ConfigManager.KEY_GROUPS, null);
                if (str != null && !str.trim().isEmpty()) {
                    jsonStr = str;
                }
            } catch (Throwable ignored) {
            }
        }

        // 解析并更新内存缓存
        if (jsonStr != null && !jsonStr.trim().isEmpty()) {
            List<EmojiGroup> list = parseGroupsJson(jsonStr);
            if (!list.isEmpty()) {
                memoryCachedGroups = list;
                AppLogger.i(TAG, "成功穿透解析到用户自定义方案: " + list.size() + " 套, 首套方案: " + list.get(0).getName());
                return list;
            }
        }

        // 通道 6：读取上一次成功的内存缓存
        if (memoryCachedGroups != null && !memoryCachedGroups.isEmpty()) {
            return memoryCachedGroups;
        }

        // 终极保护：仅当手机上从未保存过任何自定义方案时，才返回初始默认预设
        return DefaultPresets.getDefaultGroups();
    }

    private static String readStringFromFile(File file) {
        if (file == null || !file.exists() || !file.canRead() || file.length() < 5) return null;
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

    private static void cacheToQqStorage(Context context, String jsonStr) {
        if (context == null || jsonStr == null || jsonStr.trim().isEmpty()) return;
        try {
            SharedPreferences qqSp = context.getSharedPreferences(QQ_LOCAL_PREF_NAME, Context.MODE_PRIVATE);
            qqSp.edit().putString(ConfigManager.KEY_GROUPS, jsonStr).apply();
        } catch (Throwable ignored) {
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
                            } catch (Throwable ignored) {
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
}
