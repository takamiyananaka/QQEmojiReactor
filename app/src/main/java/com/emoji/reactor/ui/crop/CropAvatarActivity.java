package com.emoji.reactor.ui.crop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.emoji.reactor.R;
import com.emoji.reactor.data.ConfigManager;
import com.emoji.reactor.util.AppLogger;

import java.io.InputStream;

/**
 * QQ 头像级沉浸式交互裁剪页面 (CropAvatarActivity)
 * 支持自由缩放、拖拽取景、90度旋转纠正与 512x512 无损高清裁切写盘
 */
public class CropAvatarActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "extra_image_uri";
    public static final String EXTRA_TARGET_UIN = "extra_target_uin";

    private static final String TAG = "CropAvatarActivity";

    private ZoomCropImageView zoomCropView;
    private String mTargetUin;
    private Uri mImageUri;

    public static Intent createIntent(Context context, Uri imageUri, String targetUin) {
        Intent intent = new Intent(context, CropAvatarActivity.class);
        intent.putExtra(EXTRA_IMAGE_URI, imageUri != null ? imageUri.toString() : "");
        intent.putExtra(EXTRA_TARGET_UIN, targetUin);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏深色沉浸式状态栏体验
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(android.graphics.Color.BLACK);
        window.setNavigationBarColor(android.graphics.Color.parseColor("#1A1A1A"));

        setContentView(R.layout.activity_crop_avatar);

        mTargetUin = getIntent().getStringExtra(EXTRA_TARGET_UIN);
        String uriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriStr != null && !uriStr.isEmpty()) {
            mImageUri = Uri.parse(uriStr);
        }

        if (mTargetUin == null || mTargetUin.trim().isEmpty() || mImageUri == null) {
            Toast.makeText(this, "参数无效，无法裁剪", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        zoomCropView = findViewById(R.id.zoom_crop_view);
        View btnCancel = findViewById(R.id.btn_crop_cancel);
        View btnRotate = findViewById(R.id.btn_crop_rotate);
        View btnConfirm = findViewById(R.id.btn_crop_confirm);

        btnCancel.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        btnRotate.setOnClickListener(v -> {
            if (zoomCropView != null) {
                zoomCropView.rotate90();
            }
        });

        btnConfirm.setOnClickListener(v -> performCropAndSave());

        loadBitmapFromUri();
    }

    private void loadBitmapFromUri() {
        try {
            // 第一阶段：防大图 OOM 下采样尺寸计算
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(mImageUri)) {
                if (is == null) {
                    Toast.makeText(this, "无法读取图片文件", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                BitmapFactory.decodeStream(is, null, opts);
            }

            int w = opts.outWidth;
            int h = opts.outHeight;
            if (w <= 0 || h <= 0) {
                Toast.makeText(this, "图片解析失败", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            int sampleSize = 1;
            int targetDim = 2048; // 上限 2048 像素，保证极佳清晰度的同时杜绝 OOM
            while ((w / sampleSize) > targetDim || (h / sampleSize) > targetDim) {
                sampleSize *= 2;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap loadedBitmap = null;
            try (InputStream is = getContentResolver().openInputStream(mImageUri)) {
                if (is != null) {
                    loadedBitmap = BitmapFactory.decodeStream(is, null, opts);
                }
            }

            if (loadedBitmap == null) {
                Toast.makeText(this, "图片载入失败", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            // 第二阶段：EXIF 旋转角自动矫正
            int rotateDegree = 0;
            try (InputStream isExif = getContentResolver().openInputStream(mImageUri)) {
                if (isExif != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.media.ExifInterface exif = new android.media.ExifInterface(isExif);
                    int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL);
                    if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90) rotateDegree = 90;
                    else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180) rotateDegree = 180;
                    else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270) rotateDegree = 270;
                }
            } catch (Exception ignored) {}

            if (rotateDegree != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotateDegree);
                Bitmap rotated = Bitmap.createBitmap(loadedBitmap, 0, 0, loadedBitmap.getWidth(), loadedBitmap.getHeight(), m, true);
                if (rotated != loadedBitmap) {
                    loadedBitmap.recycle();
                    loadedBitmap = rotated;
                }
            }

            zoomCropView.setImageBitmap(loadedBitmap);

        } catch (Throwable t) {
            AppLogger.e(TAG, "loadBitmapFromUri 异常", t);
            Toast.makeText(this, "载入图片失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void performCropAndSave() {
        if (zoomCropView == null) return;
        try {
            Bitmap croppedBitmap = zoomCropView.cropBitmap();
            if (croppedBitmap == null) {
                Toast.makeText(this, "裁剪失败，请重新调整", Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存裁剪后 512x512 高清位图至四重穿透体系
            boolean success = ConfigManager.saveCroppedAvatarBitmap(this, mTargetUin, croppedBitmap);
            if (croppedBitmap != null && !croppedBitmap.isRecycled()) {
                croppedBitmap.recycle();
            }
            if (success) {
                setResult(Activity.RESULT_OK);
                finish();
            } else {
                Toast.makeText(this, "保存裁剪头像失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "performCropAndSave 异常", t);
            Toast.makeText(this, "裁剪保存异常: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (zoomCropView != null) {
            zoomCropView.recycleBitmap();
        }
    }
}
