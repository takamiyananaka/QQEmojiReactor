package com.emoji.reactor.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.emoji.reactor.model.DefaultPresets;
import com.emoji.reactor.model.EmojiGroup;
import com.emoji.reactor.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置存储与跨进程多重持久化管理器
 * 支持 Device-Protected 存储、外部多媒体镜像备份与动态广播通知，杜绝后台杀死后配置丢失
 */
public class ConfigManager {

    private static final String TAG = "QQEmojiReactor_ConfigMgr";
    public static final String PREF_NAME = "emoji_reactor_config";
    public static final String KEY_GROUPS = "emoji_groups";
    public static final String KEY_ENABLED = "module_enabled";
    public static final String ACTION_CONFIG_CHANGED = "com.emoji.reactor.ACTION_CONFIG_CHANGED";

    private static final String JSON_KEY_ID = "id";
    private static final String JSON_KEY_NAME = "name";
    private static final String JSON_KEY_EMOJIS = "emojis";
    private static final String JSON_KEY_DELAY = "delay";

    public static boolean isModuleEnabled(Context context) {
        if (context == null) return true;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setModuleEnabled(Context context, boolean enabled) {
        if (context == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putBoolean(KEY_ENABLED, enabled).apply();

            // 发送全局广播通知 QQ 端更新
            notifyConfigChanged(context);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 保存所有表情方案列表（多重持久化）
     */
    public static boolean saveGroups(Context context, List<EmojiGroup> groups) {
        if (context == null) return false;
        try {
            JSONArray array = new JSONArray();
            if (groups != null) {
                for (EmojiGroup group : groups) {
                    if (group == null) continue;
                    JSONObject obj = new JSONObject();
                    obj.put(JSON_KEY_ID, group.getId());
                    obj.put(JSON_KEY_NAME, group.getName());
                    obj.put(JSON_KEY_DELAY, group.getDelayMs());

                    JSONArray emojisArr = new JSONArray();
                    for (com.emoji.reactor.model.GroupEmojiItem item : group.getItems()) {
                        if (item == null) continue;
                        JSONObject itemObj = new JSONObject();
                        itemObj.put("emojiId", item.getEmojiId());
                        itemObj.put("emojiType", item.getEmojiType());
                        emojisArr.put(itemObj);
                    }
                    obj.put(JSON_KEY_EMOJIS, emojisArr);

                    array.put(obj);
                }
            }

            String jsonStr = array.toString();

            // 1. 标准 SharedPreferences
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_GROUPS, jsonStr).commit();

            // 2. 写入 Device Protected Storage (如果系统支持)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context deContext = context.createDeviceProtectedStorageContext();
                    if (deContext != null) {
                        deContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putString(KEY_GROUPS, jsonStr).commit();
                    }
                } catch (Throwable ignored) {
                }
            }

            // 3. 尝试设置私有目录可读
            try {
                File dataDir = context.getDataDir();
                File spDir = new File(dataDir, "shared_prefs");
                File spFile = new File(spDir, PREF_NAME + ".xml");
                dataDir.setReadable(true, false);
                dataDir.setExecutable(true, false);
                spDir.setReadable(true, false);
                spDir.setExecutable(true, false);
                spFile.setReadable(true, false);
            } catch (Throwable ignored) {
            }

            // 4. 写入外部公开媒体目录镜像备份（/sdcard/Android/media/com.emoji.reactor/config.json）
            try {
                File[] mediaDirs = context.getExternalMediaDirs();
                if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                    File mediaDir = mediaDirs[0];
                    if (!mediaDir.exists()) mediaDir.mkdirs();
                    File backupFile = new File(mediaDir, "config.json");
                    try (FileOutputStream fos = new FileOutputStream(backupFile)) {
                        fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                        fos.flush();
                    }
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "写入外部媒体备份异常", t);
            }

            // 4.1 写入系统级公共共享目录备份（/sdcard/Download/QQEmojiReactor/config.json，QQ与模块无障碍互通）
            try {
                File sharedDir = new File("/sdcard/Download/QQEmojiReactor");
                if (!sharedDir.exists()) sharedDir.mkdirs();
                File sharedFile = new File(sharedDir, "config.json");
                try (FileOutputStream fos = new FileOutputStream(sharedFile)) {
                    fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "写入公共共享备份异常", t);
            }

            // 5. 发送系统广播通知 QQ 进程即刻更新
            notifyConfigChanged(context);

            return true;
        } catch (Throwable t) {
            AppLogger.e(TAG, "saveGroups 发生异常", t);
            return false;
        }
    }

    private static void notifyConfigChanged(Context context) {
        try {
            Intent intent = new Intent(ACTION_CONFIG_CHANGED);
            intent.setPackage("com.tencent.mobileqq");
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 加载表情方案列表
     */
    public static List<EmojiGroup> loadGroups(Context context) {
        List<EmojiGroup> list = new ArrayList<>();
        if (context == null) {
            return DefaultPresets.getDefaultGroups();
        }

        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String jsonStr = sp.getString(KEY_GROUPS, null);

            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                List<EmojiGroup> defaultGroups = DefaultPresets.getDefaultGroups();
                saveGroups(context, defaultGroups);
                return defaultGroups;
            }

            boolean needsMigration = false;
            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String id = obj.optString(JSON_KEY_ID);
                String name = obj.optString(JSON_KEY_NAME, "方案" + (i + 1));
                int delay = obj.optInt(JSON_KEY_DELAY, 100);

                List<com.emoji.reactor.model.GroupEmojiItem> items = new ArrayList<>();
                JSONArray emojisArr = obj.optJSONArray(JSON_KEY_EMOJIS);
                if (emojisArr != null) {
                    for (int j = 0; j < emojisArr.length(); j++) {
                        Object elem = emojisArr.get(j);
                        if (elem instanceof JSONObject) {
                            JSONObject itemObj = (JSONObject) elem;
                            String emojiId = itemObj.optString("emojiId", "");
                            long emojiType = itemObj.optLong("emojiType", 1L);
                            items.add(new com.emoji.reactor.model.GroupEmojiItem(emojiId, emojiType));
                        } else if (elem instanceof Number) {
                            needsMigration = true;
                            int legacyId = ((Number) elem).intValue();
                            items.add(new com.emoji.reactor.model.GroupEmojiItem(legacyId));
                        } else if (elem instanceof String) {
                            needsMigration = true;
                            String strVal = (String) elem;
                            try {
                                int legacyId = Integer.parseInt(strVal);
                                items.add(new com.emoji.reactor.model.GroupEmojiItem(legacyId));
                            } catch (Throwable ignored) {
                                items.add(new com.emoji.reactor.model.GroupEmojiItem(strVal, 1L));
                            }
                        }
                    }
                }

                list.add(com.emoji.reactor.model.EmojiGroup.fromItems(id, name, items, delay));
            }

            if (list.isEmpty()) {
                return DefaultPresets.getDefaultGroups();
            }

            // 若检测到旧版配置，自动原地升级为新版标准结构
            if (needsMigration) {
                AppLogger.i(TAG, "检测到旧版表情配置，正在执行无感升级并持久化最新结构...");
                saveGroups(context, list);
            }
        } catch (Throwable t) {
            return DefaultPresets.getDefaultGroups();
        }

        return list;
    }
}
