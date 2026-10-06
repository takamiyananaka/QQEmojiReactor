package com.emoji.reactor.util;

import android.os.Environment;

import java.io.File;

/**
 * Android 标准存储路径解析器 (StoragePaths)
 * 严格遵循 Android Scoped Storage 规范与多用户隔离兼容性 (彻底杜绝硬编码 /sdcard/)
 */
public class StoragePaths {

    public static File getExternalStorageRoot() {
        try {
            File dir = Environment.getExternalStorageDirectory();
            if (dir != null) return dir;
        } catch (Exception e) {
            AppLogger.d("StoragePaths", "获取外部存储根目录回退: " + e.getMessage());
        }
        return new File("/sdcard");
    }

    public static File getSharedDownloadDir() {
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadDir != null) {
                return new File(downloadDir, "QQEmojiReactor");
            }
        } catch (Exception e) {
            AppLogger.d("StoragePaths", "获取Download目录回退: " + e.getMessage());
        }
        return new File(getExternalStorageRoot(), "Download/QQEmojiReactor");
    }

    public static File getSharedConfigFile() {
        return new File(getSharedDownloadDir(), "config.json");
    }

    public static File getSafeMediaCacheDir() {
        return new File(getExternalStorageRoot(), "Android/media/com.emoji.reactor/.cache_emojis");
    }

    public static File getSafeMediaConfigFile() {
        return new File(getExternalStorageRoot(), "Android/media/com.emoji.reactor/config.json");
    }

    public static File getLegacyDownloadLiveDir() {
        return new File(getSharedDownloadDir(), "live_emojis");
    }

    public static File getLegacyQqMediaDir() {
        return new File(getExternalStorageRoot(), "Android/media/com.tencent.mobileqq/live_emojis");
    }

    public static File getLegacyQqDumpFile() {
        return new File(getExternalStorageRoot(), "Android/media/com.tencent.mobileqq/qq_live_faces.json");
    }

    public static File getCustomMenuIconFile() {
        return new File(getSharedDownloadDir(), "custom_menu_icon.png");
    }

    public static File getSafeMediaCustomMenuIconFile() {
        return new File(getExternalStorageRoot(), "Android/media/com.emoji.reactor/custom_menu_icon.png");
    }

    public static File getDefaultMenuIconFile() {
        return new File(getSharedDownloadDir(), "default_menu_icon.png");
    }

    public static File getSafeMediaDefaultMenuIconFile() {
        return new File(getExternalStorageRoot(), "Android/media/com.emoji.reactor/default_menu_icon.png");
    }

    public static File getSafeMediaAvatarsDir() {
        File dir = new File(getExternalStorageRoot(), "Android/media/com.emoji.reactor/custom_avatars");
        if (!dir.exists()) dir.mkdirs();
        try {
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
        } catch (Exception e) {
            AppLogger.d("StoragePaths", "设置目录权限回退: " + e.getMessage());
        }
        return dir;
    }

    public static File getSharedAvatarsDir() {
        File dir = new File(getSharedDownloadDir(), "custom_avatars");
        if (!dir.exists()) dir.mkdirs();
        try {
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
        } catch (Exception e) {
            AppLogger.d("StoragePaths", "设置目录权限回退: " + e.getMessage());
        }
        return dir;
    }

    public static File getAvatarFile(String uin) {
        return new File(getSafeMediaAvatarsDir(), uin + ".png");
    }

    public static File getQqMediaRootDir() {
        return new File(getExternalStorageRoot(), "Android/media/com.tencent.mobileqq");
    }

    public static File getQqMediaEmojiReactorDir() {
        File dir = new File(getQqMediaRootDir(), "emoji_reactor");
        if (!dir.exists()) dir.mkdirs();
        try {
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        } catch (Exception e) {
            AppLogger.d("StoragePaths", "设置QQ媒体目录权限回退: " + e.getMessage());
        }
        return dir;
    }

    public static File getQqMediaConfigFile() {
        return new File(getQqMediaEmojiReactorDir(), "config.json");
    }

    public static File getQqMediaAvatarsDir() {
        File dir = new File(getQqMediaEmojiReactorDir(), "custom_avatars");
        if (!dir.exists()) dir.mkdirs();
        try {
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        } catch (Exception e) {
            AppLogger.d("StoragePaths", "设置QQ媒体目录权限回退: " + e.getMessage());
        }
        return dir;
    }

    public static File getQqAvatarFile(String uin) {
        return new File(getQqMediaAvatarsDir(), uin + ".png");
    }
}
