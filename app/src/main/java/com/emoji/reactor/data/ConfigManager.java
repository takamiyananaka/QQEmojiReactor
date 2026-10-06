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
import com.emoji.reactor.model.CustomAvatarItem;
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
    public static final String KEY_CUSTOM_AVATARS = "custom_avatars";
    public static final String ACTION_CONFIG_CHANGED = "com.emoji.reactor.ACTION_CONFIG_CHANGED";

    private static final String JSON_KEY_ID = "id";
    private static final String JSON_KEY_NAME = "name";
    private static final String JSON_KEY_EMOJIS = "emojis";
    private static final String JSON_KEY_DELAY = "delay";
    private static final java.util.concurrent.ExecutorService sDiskWriteExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "reactor-disk-writer");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    public static boolean isFeatureEnabled(Context context, String key, boolean defaultVal) {
        if (context == null) return defaultVal;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            return sp.getBoolean(key, defaultVal);
        } catch (Throwable t) {
            return defaultVal;
        }
    }

    public static void setFeatureEnabled(Context context, String key, boolean enabled) {
        if (context == null || key == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putBoolean(key, enabled).apply();

            // 写入 Device Protected Storage (如果系统支持)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context deContext = context.createDeviceProtectedStorageContext();
                    if (deContext != null) {
                        deContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(key, enabled).apply();
                    }
                } catch (Exception e) {
                    AppLogger.d(TAG, "写入DeviceProtectedStorage安全跳过: " + e.getMessage());
                }
            }

            // 同步写入四重穿透共享文件
            syncFullConfigToFile(context);

            notifyConfigChanged(context);
        } catch (Exception e) {
            AppLogger.d(TAG, "setFeatureEnabled 发生异常: " + e.getMessage());
        }
    }

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

            // 写入 Device Protected Storage (如果系统支持)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context deContext = context.createDeviceProtectedStorageContext();
                    if (deContext != null) {
                        deContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(KEY_ENABLED, enabled).apply();
                    }
                } catch (Exception e) {
                    AppLogger.d(TAG, "写入DeviceProtectedStorage安全跳过: " + e.getMessage());
                }
            }

            // 同步写入四重穿透共享文件
            syncFullConfigToFile(context);

            // 发送全局广播通知 QQ 端更新
            notifyConfigChanged(context);
        } catch (Exception e) {
            AppLogger.d(TAG, "setModuleEnabled 发生异常: " + e.getMessage());
        }
    }

    /**
     * 将包含所有开关状态、自定义图标与表情方案的全量配置持久化同步至系统共享与媒体目录
     */
    public static void syncFullConfigToFile(Context context) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        sDiskWriteExecutor.execute(() -> syncFullConfigInternal(appCtx));
    }

    private static void writeAtomicTextFile(File targetFile, String content) {
        if (targetFile == null || content == null) return;
        try {
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            android.util.AtomicFile atomicFile = new android.util.AtomicFile(targetFile);
            FileOutputStream fos = atomicFile.startWrite();
            boolean success = false;
            try {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Exception e) {
                    AppLogger.d(TAG, "fos sync 安全跳过: " + e.getMessage());
                }
                atomicFile.finishWrite(fos);
                success = true;
            } finally {
                if (!success) {
                    atomicFile.failWrite(fos);
                }
            }
            try {
                targetFile.setReadable(true, false);
                targetFile.setWritable(true, false);
                targetFile.setLastModified(System.currentTimeMillis());
            } catch (Exception e) {
                AppLogger.d(TAG, "writeAtomicTextFile 设置权限回退: " + e.getMessage());
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "writeAtomicTextFile 发生异常: " + e.getMessage());
        }
    }

    private static void syncFullConfigInternal(Context context) {
        if (context == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            JSONObject fullConfig = new JSONObject();

            fullConfig.put(KEY_ENABLED, sp.getBoolean(KEY_ENABLED, true));
            fullConfig.put(KEY_CUSTOM_ICON, sp.getString(KEY_CUSTOM_ICON, ""));

            for (java.util.Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
                if (entry.getValue() instanceof Boolean) {
                    fullConfig.put(entry.getKey(), (Boolean) entry.getValue());
                }
            }

            String groupsJson = sp.getString(KEY_GROUPS, "[]");
            fullConfig.put(KEY_GROUPS, new JSONArray(groupsJson));

            String avatarsJson = sp.getString(KEY_CUSTOM_AVATARS, "[]");
            fullConfig.put(KEY_CUSTOM_AVATARS, new JSONArray(avatarsJson));

            String fullConfigStr = fullConfig.toString();

            // 0. 写入 QQ 自身媒体目录镜像 (AtomicFile 原子写盘)
            File qqConfigFile = StoragePaths.getQqMediaConfigFile();
            writeAtomicTextFile(qqConfigFile, fullConfigStr);

            // 同步确保所有头像实体文件均镜像写入 QQ 媒体目录，强制更新并清理已删除项
            List<CustomAvatarItem> avatarList = loadCustomAvatars(context);
            java.util.Set<String> activeUinSet = new java.util.HashSet<>();
            for (CustomAvatarItem aItem : avatarList) {
                if (aItem != null && !aItem.getUin().isEmpty() && aItem.isEnabled()) {
                    activeUinSet.add(aItem.getUin());
                    File qqAvatarF = StoragePaths.getQqAvatarFile(aItem.getUin());
                    if (!aItem.getImagePath().isEmpty()) {
                        File srcF = new File(aItem.getImagePath());
                        if (srcF.exists()) {
                            Bitmap bm = BitmapFactory.decodeFile(srcF.getAbsolutePath());
                            if (bm != null) {
                                saveBitmapToFile(bm, qqAvatarF);
                                qqAvatarF.setLastModified(aItem.getLastModified());
                            }
                        }
                    }
                }
            }

            // 物理清理被删除/禁用的孤儿头像文件
            File qqAvatarsDir = StoragePaths.getQqMediaAvatarsDir();
            if (qqAvatarsDir.exists() && qqAvatarsDir.isDirectory()) {
                File[] orphanFiles = qqAvatarsDir.listFiles();
                if (orphanFiles != null) {
                    for (File of : orphanFiles) {
                        String name = of.getName();
                        if (name.endsWith(".png")) {
                            String u = name.substring(0, name.length() - 4);
                            if (!activeUinSet.contains(u)) {
                                of.delete();
                            }
                        }
                    }
                }
            }

            // 1. 写入外部公开媒体目录镜像 (Scoped Storage)
            File mediaFile = StoragePaths.getSafeMediaConfigFile();
            writeAtomicTextFile(mediaFile, fullConfigStr);

            // 2. 写入系统级公共共享目录备份 (Download/QQEmojiReactor)
            File sharedFile = StoragePaths.getSharedConfigFile();
            writeAtomicTextFile(sharedFile, fullConfigStr);

            // 3. 确保私有 SharedPreferences 文件对外部可读
            try {
                File dataDir = context.getDataDir();
                File spDir = new File(dataDir, "shared_prefs");
                File spFile = new File(spDir, PREF_NAME + ".xml");
                dataDir.setReadable(true, false);
                dataDir.setExecutable(true, false);
                spDir.setReadable(true, false);
                spDir.setExecutable(true, false);
                spFile.setReadable(true, false);
            } catch (Exception e) {
                AppLogger.d(TAG, "设置SP文件权限安全跳过: " + e.getMessage());
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "syncFullConfigToFile 异常", t);
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
                } catch (Exception e) {
                    AppLogger.d(TAG, "deContext saveGroups 安全跳过: " + e.getMessage());
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
            } catch (Exception e) {
                AppLogger.d(TAG, "设置SP权限安全跳过: " + e.getMessage());
            }

            // 4. 写入全量配置到外部公开媒体目录与公共共享目录
            syncFullConfigToFile(context);

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
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            if (context != null) {
                SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                String avatarsJson = sp.getString(KEY_CUSTOM_AVATARS, "[]");
                intent.putExtra(KEY_CUSTOM_AVATARS, avatarsJson);
                context.sendBroadcast(intent);
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "notifyConfigChanged 安全跳过: " + e.getMessage());
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
                            } catch (NumberFormatException e) {
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
                } catch (Exception e) {
                    AppLogger.d(TAG, "deContext saveCustomMenuIcon 安全跳过: " + e.getMessage());
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
                } catch (Exception e) {
                    AppLogger.d(TAG, "deContext resetCustomMenuIcon 安全跳过: " + e.getMessage());
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
            } catch (Exception e) {
                AppLogger.d(TAG, "hasCustomMenuIcon 安全跳过: " + e.getMessage());
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
        } catch (Exception e) {
            AppLogger.d(TAG, "getCurrentMenuIconBitmap 安全跳过: " + e.getMessage());
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
            try {
                targetFile.setReadable(true, false);
            } catch (Exception e) {
                AppLogger.d(TAG, "targetFile setReadable 安全跳过: " + e.getMessage());
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "saveBitmapToFile 安全跳过: " + e.getMessage());
        }
    }

    public static List<CustomAvatarItem> loadCustomAvatars(Context context) {
        List<CustomAvatarItem> list = new ArrayList<>();
        if (context == null) return list;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String jsonStr = sp.getString(KEY_CUSTOM_AVATARS, "[]");
            if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                JSONArray arr = new JSONArray(jsonStr);
                for (int i = 0; i < arr.length(); i++) {
                    CustomAvatarItem item = CustomAvatarItem.fromJson(arr.getJSONObject(i));
                    if (item != null && !item.getUin().isEmpty()) {
                        list.add(item);
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "loadCustomAvatars 异常", t);
        }
        return list;
    }

    public static String encodeFileToBase64(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";
        try {
            File f = new File(filePath);
            if (!f.exists() || f.length() == 0) return "";
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                byte[] bytes = new byte[(int) f.length()];
                int r = fis.read(bytes);
                if (r > 0) {
                    return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                }
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "encodeFileToBase64 安全跳过: " + e.getMessage());
        }
        return "";
    }

    public static boolean saveCustomAvatars(Context context, List<CustomAvatarItem> items) {
        if (context == null) return false;
        try {
            JSONArray arr = new JSONArray();
            if (items != null) {
                for (CustomAvatarItem item : items) {
                    if (item != null && !item.getUin().isEmpty()) {
                        File f = new File(item.getImagePath());
                        if (!f.exists() || f.length() == 0) {
                            f = StoragePaths.getAvatarFile(item.getUin());
                        }
                        if (f.exists() && f.length() > 0) {
                            item.setImagePath(f.getAbsolutePath());
                            item.setLastModified(f.lastModified());
                        }
                        arr.put(item.toJson());
                    }
                }
            }
            String jsonStr = arr.toString();

            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_CUSTOM_AVATARS, jsonStr).apply();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context deContext = context.createDeviceProtectedStorageContext();
                    if (deContext != null) {
                        deContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putString(KEY_CUSTOM_AVATARS, jsonStr).apply();
                    }
                } catch (Exception e) {
                    AppLogger.d(TAG, "deContext saveCustomAvatars 安全跳过: " + e.getMessage());
                }
            }

            syncFullConfigToFile(context);
            notifyConfigChanged(context);
            return true;
        } catch (Throwable t) {
            AppLogger.e(TAG, "saveCustomAvatars 异常", t);
            return false;
        }
    }

    /**
     * 从相册选择的 Uri 居中裁剪为 1:1 512x512 高清位图并保存
     * 具备严格的两阶段下采样 (inSampleSize) 与 EXIF 旋转角自适应校正，彻底杜绝大图 OOM
     */
    public static String saveAndCropAvatarImage(Context context, String uin, Uri imageUri) {
        if (context == null || uin == null || uin.trim().isEmpty() || imageUri == null) return null;
        uin = uin.trim();
        Bitmap raw = null;
        Bitmap rotated = null;
        Bitmap cropped = null;
        Bitmap scaled = null;
        try {
            // 第一阶段：仅读取边界尺寸计算下采样比率，防止大图瞬间打爆内存
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream isBounds = context.getContentResolver().openInputStream(imageUri)) {
                if (isBounds == null) return null;
                BitmapFactory.decodeStream(isBounds, null, opts);
            }

            int origWidth = opts.outWidth;
            int origHeight = opts.outHeight;
            if (origWidth <= 0 || origHeight <= 0) return null;

            int sampleSize = 1;
            int targetDim = 1024;
            while ((origWidth / sampleSize) > targetDim || (origHeight / sampleSize) > targetDim) {
                sampleSize *= 2;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

            // 第二阶段：安全加载采样后的位图
            try (InputStream is = context.getContentResolver().openInputStream(imageUri)) {
                if (is == null) return null;
                raw = BitmapFactory.decodeStream(is, null, opts);
            }
            if (raw == null) return null;

            // 第三阶段：检查并矫正相机 EXIF 旋转角
            int rotateDegree = 0;
            try (InputStream isExif = context.getContentResolver().openInputStream(imageUri)) {
                if (isExif != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.media.ExifInterface exif = new android.media.ExifInterface(isExif);
                    int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL);
                    if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90) rotateDegree = 90;
                    else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180) rotateDegree = 180;
                    else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270) rotateDegree = 270;
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "isExif 获取方向安全跳过: " + e.getMessage());
            }

            if (rotateDegree != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotateDegree);
                rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                if (rotated != raw) {
                    raw.recycle();
                    raw = rotated;
                }
            }

            // 第四阶段：1:1 中心居中裁剪
            int width = raw.getWidth();
            int height = raw.getHeight();
            int minEdge = Math.min(width, height);
            int x = (width - minEdge) / 2;
            int y = (height - minEdge) / 2;

            cropped = Bitmap.createBitmap(raw, x, y, minEdge, minEdge);
            if (cropped != raw) {
                raw.recycle();
            }

            scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true);
            if (scaled != cropped) {
                cropped.recycle();
            }

            File targetFile = StoragePaths.getAvatarFile(uin);
            saveBitmapToFile(scaled, targetFile);

            long now = System.currentTimeMillis();
            try {
                targetFile.setLastModified(now);
            } catch (Exception e) {
                AppLogger.d(TAG, "targetFile setLastModified 安全跳过: " + e.getMessage());
            }

            try {
                File qqAvatarFile = StoragePaths.getQqAvatarFile(uin);
                saveBitmapToFile(scaled, qqAvatarFile);
                qqAvatarFile.setLastModified(now);
            } catch (Exception e) {
                AppLogger.d(TAG, "qqAvatarFile setLastModified 安全跳过: " + e.getMessage());
            }

            try {
                File sharedMirror = new File(StoragePaths.getSharedAvatarsDir(), uin + ".png");
                saveBitmapToFile(scaled, sharedMirror);
                sharedMirror.setLastModified(now);
            } catch (Exception e) {
                AppLogger.d(TAG, "sharedMirror setLastModified 安全跳过: " + e.getMessage());
            }

            // 保持内存缓存更新
            BitmapCacheManager.loadBitmap(targetFile.getAbsolutePath(), 512, 512);

            return targetFile.getAbsolutePath();
        } catch (Throwable t) {
            AppLogger.e(TAG, "saveAndCropAvatarImage 异常: " + uin, t);
            return null;
        } finally {
            if (scaled != null && !scaled.isRecycled()) {
                // scaled 已保存文件，可保留或由系统回收
            }
        }
    }

    public static void removeCustomAvatar(Context context, String uin) {
        if (context == null || uin == null || uin.trim().isEmpty()) return;
        uin = uin.trim();
        List<CustomAvatarItem> items = loadCustomAvatars(context);
        boolean removed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).getUin().equals(uin)) {
                items.remove(i);
                removed = true;
            }
        }
        if (removed) {
            try {
                File f = StoragePaths.getAvatarFile(uin);
                if (f.exists()) f.delete();
                File qf = StoragePaths.getQqAvatarFile(uin);
                if (qf.exists()) qf.delete();
                File sf = new File(StoragePaths.getSharedAvatarsDir(), uin + ".png");
                if (sf.exists()) sf.delete();
            } catch (Exception e) {
                AppLogger.d(TAG, "removeCustomAvatar 物理删除安全跳过: " + e.getMessage());
            }
            saveCustomAvatars(context, items);
        }
    }

    public static void setCustomAvatarEnabled(Context context, String uin, boolean enabled) {
        if (context == null || uin == null || uin.trim().isEmpty()) return;
        uin = uin.trim();
        List<CustomAvatarItem> items = loadCustomAvatars(context);
        boolean changed = false;
        for (CustomAvatarItem item : items) {
            if (item.getUin().equals(uin)) {
                item.setEnabled(enabled);
                changed = true;
                break;
            }
        }
        if (changed) {
            saveCustomAvatars(context, items);
        }
    }
}
