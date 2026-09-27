package com.emoji.reactor.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;

/**
 * 高性能轻量级 Bitmap 内存缓存与安全解码器 (BitmapCacheManager)
 * 1. 采用 LruCache 内存池，杜绝列表快速滑动时重复解码与 GC 抖动
 * 2. 带有 inSampleSize 目标尺寸下采样，杜绝加载大图引发 OOM
 * 3. 线程安全，多 Adapter 共享复用
 */
public class BitmapCacheManager {

    private static final String TAG = "BitmapCacheManager";

    // 默认分配应用可用最大堆内存的 1/8 作为图片缓存池
    private static final int MAX_MEMORY = (int) (Runtime.getRuntime().maxMemory() / 1024);
    private static final int CACHE_SIZE = Math.max(1024 * 8, MAX_MEMORY / 8);

    private static final LruCache<String, Bitmap> memoryCache = new LruCache<String, Bitmap>(CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    /**
     * 从缓存读取或按需安全下采样解码图片文件
     *
     * @param filePath 磁盘文件绝对路径
     * @param reqWidth 目标显示宽度 (px)
     * @param reqHeight 目标显示高度 (px)
     * @return 内存中的 Bitmap 实例，解码失败返回 null
     */
    public static Bitmap loadBitmap(String filePath, int reqWidth, int reqHeight) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }

        String cacheKey = filePath + "_" + reqWidth + "x" + reqHeight;
        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        File file = new File(filePath);
        if (!file.exists() || !file.canRead() || file.length() == 0) {
            return null;
        }

        try {
            // 第一次仅读取边界元数据获取原始宽高
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, options);

            // 计算缩放比 inSampleSize
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap bitmap = BitmapFactory.decodeFile(filePath, options);
            if (bitmap != null) {
                memoryCache.put(cacheKey, bitmap);
                return bitmap;
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "解码图片失败: " + filePath, t);
        }

        return null;
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (reqWidth <= 0 || reqHeight <= 0) {
            return 1;
        }

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    public static void clearCache() {
        memoryCache.evictAll();
    }
}
