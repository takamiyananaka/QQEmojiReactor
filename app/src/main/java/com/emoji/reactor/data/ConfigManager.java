package com.emoji.reactor.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.emoji.reactor.R;
import com.emoji.reactor.model.DefaultPresets;
import com.emoji.reactor.model.EmojiGroup;
import com.emoji.reactor.util.AppLogger;
import com.emoji.reactor.util.BitmapCacheManager;
import com.emoji.reactor.util.StoragePaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
    public static final String KEY_CUSTOM_ICON = "custom_menu_icon_base64";
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

            // 1. 标准 SharedPreferences (非阻塞异步 apply)
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_GROUPS, jsonStr).apply();

            // 2. 写入 Device Protected Storage (如果系统支持)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context deContext = context.createDeviceProtectedStorageContext();
                    if (deContext != null) {
                        deContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putString(KEY_GROUPS, jsonStr).apply();
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

            // 4. 写入外部公开媒体目录镜像备份 (标准 Scoped Storage 路径)
            try {
                File backupFile = com.emoji.reactor.util.StoragePaths.getSafeMediaConfigFile();
                File parent = backupFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(backupFile)) {
                    fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "写入外部媒体备份异常", t);
            }

            // 4.1 写入系统级公共共享目录备份 (标准 Download 路径)
            try {
                File sharedFile = com.emoji.reactor.util.StoragePaths.getSharedConfigFile();
                File parent = sharedFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
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

    /**
     * 确保默认菜单图标已安全导出到共享目录，供 QQ 进程无权限障碍读取
     */
    public static void ensureDefaultMenuIconExported(Context context) {
        if (context == null) return;
        try {
            File defaultFile = StoragePaths.getDefaultMenuIconFile();
            File safeMediaFile = StoragePaths.getSafeMediaDefaultMenuIconFile();
            if (defaultFile.exists() && defaultFile.length() > 0 && safeMediaFile.exists() && safeMediaFile.length() > 0) {
                return;
            }

            Drawable d = ContextCompat.getDrawable(context, R.mipmap.ic_launcher);
            if (d != null) {
                Bitmap bm = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bm);
                d.setBounds(0, 0, 128, 128);
                d.draw(canvas);

                saveBitmapToFile(bm, defaultFile);
                saveBitmapToFile(bm, safeMediaFile);
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "导出默认菜单图标异常", t);
        }
    }

    /**
     * 保存用户从相册挑选上传的自定义菜单图标（自动裁剪缩放到 128x128 并生成 Base64 与多重镜像）
     */
    public static boolean saveCustomMenuIcon(Context context, Uri imageUri) {
        if (context == null || imageUri == null) return false;
        try {
            InputStream is = context.getContentResolver().openInputStream(imageUri);
            if (is == null) return false;
            Bitmap original = BitmapFactory.decodeStream(is);
            is.close();
            if (original == null) return false;

            Bitmap scaled = Bitmap.createScaledBitmap(original, 128, 128, true);

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] bytes = baos.toByteArray();
            String base64Str = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);

            // 1. 保存到主 App SharedPreferences，供 ContentProvider 零权限跨进程直接传输
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_CUSTOM_ICON, base64Str).apply();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context deContext = context.createDeviceProtectedStorageContext();
                    if (deContext != null) {
                        deContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putString(KEY_CUSTOM_ICON, base64Str).apply();
                    }
                } catch (Throwable ignored) {
                }
            }

            // 2. 写入存储镜像文件兜底
            File customFile = StoragePaths.getCustomMenuIconFile();
            File safeMediaFile = StoragePaths.getSafeMediaCustomMenuIconFile();

            saveBitmapToFile(scaled, customFile);
            saveBitmapToFile(scaled, safeMediaFile);

            BitmapCacheManager.clearCache();
            notifyConfigChanged(context);
            return true;
        } catch (Throwable t) {
            AppLogger.e(TAG, "保存自定义菜单图标异常", t);
            return false;
        }
    }

    /**
     * 恢复默认菜单图标（删除自定义图标并刷新）
     */
    public static boolean resetCustomMenuIcon(Context context) {
        if (context == null) return false;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().remove(KEY_CUSTOM_ICON).apply();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context deContext = context.createDeviceProtectedStorageContext();
                    if (deContext != null) {
                        deContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().remove(KEY_CUSTOM_ICON).apply();
                    }
                } catch (Throwable ignored) {
                }
            }

            File customFile = StoragePaths.getCustomMenuIconFile();
            if (customFile.exists()) customFile.delete();

            File safeMediaFile = StoragePaths.getSafeMediaCustomMenuIconFile();
            if (safeMediaFile.exists()) safeMediaFile.delete();

            BitmapCacheManager.clearCache();
            ensureDefaultMenuIconExported(context);
            notifyConfigChanged(context);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasCustomMenuIcon(Context context) {
        if (context != null) {
            try {
                SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                String b64 = sp.getString(KEY_CUSTOM_ICON, null);
                if (b64 != null && !b64.isEmpty()) return true;
            } catch (Throwable ignored) {
            }
        }
        File customFile = StoragePaths.getCustomMenuIconFile();
        if (customFile.exists() && customFile.length() > 0) return true;
        File safeMediaFile = StoragePaths.getSafeMediaCustomMenuIconFile();
        return safeMediaFile.exists() && safeMediaFile.length() > 0;
    }

    public static Bitmap getCurrentMenuIconBitmap(Context context) {
        if (context == null) return null;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String b64 = sp.getString(KEY_CUSTOM_ICON, null);
            if (b64 != null && !b64.isEmpty()) {
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                if (bytes != null && bytes.length > 0) {
                    Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bm != null) return bm;
                }
            }
        } catch (Throwable ignored) {
        }

        File customFile = StoragePaths.getCustomMenuIconFile();
        if (!customFile.exists() || customFile.length() == 0) {
            customFile = StoragePaths.getSafeMediaCustomMenuIconFile();
        }
        if (customFile.exists() && customFile.length() > 0) {
            return BitmapCacheManager.loadBitmap(customFile.getAbsolutePath(), 128, 128);
        }

        File defaultFile = StoragePaths.getDefaultMenuIconFile();
        if (!defaultFile.exists() || defaultFile.length() == 0) {
            defaultFile = StoragePaths.getSafeMediaDefaultMenuIconFile();
        }
        if (defaultFile.exists() && defaultFile.length() > 0) {
            return BitmapCacheManager.loadBitmap(defaultFile.getAbsolutePath(), 128, 128);
        }

        Drawable d = ContextCompat.getDrawable(context, R.mipmap.ic_launcher);
        if (d != null) {
            Bitmap bm = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bm);
            d.setBounds(0, 0, 128, 128);
            d.draw(canvas);
            return bm;
        }
        return null;
    }

    private static void saveBitmapToFile(Bitmap bm, File targetFile) {
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
}
