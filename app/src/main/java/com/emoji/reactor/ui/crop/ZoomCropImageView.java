package com.emoji.reactor.ui.crop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * QQ 头像级可交互裁剪视图 (ZoomCropImageView)
 * 支持双指平滑手势缩放、自由拖拽平移、双击放大与 100% 边界防露黑边算法
 * 采用硬件加速友好的 EVEN_ODD 圆形取景遮罩，高保真还原手机 QQ 头像裁剪体验
 */
public class ZoomCropImageView extends View {

    private Bitmap mBitmap;
    private final Matrix mCurrentMatrix = new Matrix();

    private final Paint mMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCircleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mMaskPath = new Path();

    private float mCenterX;
    private float mCenterY;
    private float mCircleRadius;

    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;

    private float mLastTouchX;
    private float mLastTouchY;
    private boolean mIsDragging;
    private boolean mIsInitialized = false;

    private static final float MIN_SCALE_MARGIN = 1.0f;
    private static final float MAX_SCALE = 5.0f;

    public ZoomCropImageView(Context context) {
        this(context, null);
    }

    public ZoomCropImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomCropImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // 暗黑色半透明背景遮罩 (70% 纯黑)
        mMaskPaint.setColor(Color.parseColor("#B3000000"));
        mMaskPaint.setStyle(Paint.Style.FILL);

        // 圆形取景框白色细腻边缘描边
        mCircleStrokePaint.setColor(Color.parseColor("#80FFFFFF"));
        mCircleStrokePaint.setStyle(Paint.Style.STROKE);
        mCircleStrokePaint.setStrokeWidth(dp2px(1.5f));

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (mBitmap == null) return false;
                float scaleFactor = detector.getScaleFactor();
                mCurrentMatrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                checkBoundsAndScale();
                invalidate();
                return true;
            }
        });

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mBitmap == null) return false;
                // 双击自动在 1.0x 基础覆盖与 2.0x 放大之间平滑切换
                float currentScale = getMatrixScale();
                float baseScale = getBaseMinScale();
                float targetScale = (currentScale > baseScale * 1.3f) ? baseScale : baseScale * 2.0f;
                float factor = targetScale / currentScale;
                mCurrentMatrix.postScale(factor, factor, e.getX(), e.getY());
                checkBoundsAndScale();
                invalidate();
                return true;
            }
        });
    }

    public void setImageBitmap(Bitmap bitmap) {
        this.mBitmap = bitmap;
        this.mIsInitialized = false;
        if (getWidth() > 0 && getHeight() > 0) {
            initMatrixToCenterCrop();
        }
        requestLayout();
        invalidate();
    }

    public void recycleBitmap() {
        if (mBitmap != null && !mBitmap.isRecycled()) {
            mBitmap.recycle();
            mBitmap = null;
        }
    }

    public void rotate90() {
        if (mBitmap == null) return;
        Matrix rotateMatrix = new Matrix();
        rotateMatrix.postRotate(90);
        Bitmap rotated = Bitmap.createBitmap(mBitmap, 0, 0, mBitmap.getWidth(), mBitmap.getHeight(), rotateMatrix, true);
        if (rotated != mBitmap) {
            mBitmap.recycle();
            mBitmap = rotated;
        }
        mIsInitialized = false;
        initMatrixToCenterCrop();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mCenterX = w / 2.0f;
        mCenterY = h / 2.0f;
        // 圆形裁剪框直径约为屏幕较短边缘减去 48dp 边距
        mCircleRadius = (Math.min(w, h) - dp2px(48f)) / 2.0f;

        mMaskPath.reset();
        mMaskPath.addRect(0, 0, w, h, Path.Direction.CW);
        mMaskPath.addCircle(mCenterX, mCenterY, mCircleRadius, Path.Direction.CCW);
        mMaskPath.setFillType(Path.FillType.EVEN_ODD);

        if (!mIsInitialized && mBitmap != null) {
            initMatrixToCenterCrop();
        }
    }

    private void initMatrixToCenterCrop() {
        if (getWidth() <= 0 || getHeight() <= 0 || mBitmap == null) return;
        mCurrentMatrix.reset();

        float bmW = mBitmap.getWidth();
        float bmH = mBitmap.getHeight();

        // 初始缩放比例：刚好完整覆盖圆形窗口
        float minScale = (mCircleRadius * 2.0f) / Math.min(bmW, bmH);
        mCurrentMatrix.postScale(minScale, minScale);

        // 居中摆放至圆形中心
        float scaledW = bmW * minScale;
        float scaledH = bmH * minScale;
        float dx = mCenterX - scaledW / 2.0f;
        float dy = mCenterY - scaledH / 2.0f;
        mCurrentMatrix.postTranslate(dx, dy);

        checkBoundsAndScale();
        mIsInitialized = true;
    }

    private float getBaseMinScale() {
        if (mBitmap == null) return 1.0f;
        return (mCircleRadius * 2.0f) / Math.min(mBitmap.getWidth(), mBitmap.getHeight());
    }

    private float getMatrixScale() {
        float[] values = new float[9];
        mCurrentMatrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private void checkBoundsAndScale() {
        if (mBitmap == null || mCircleRadius <= 0) return;

        RectF r = new RectF(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
        mCurrentMatrix.mapRect(r);

        float cropLeft = mCenterX - mCircleRadius;
        float cropTop = mCenterY - mCircleRadius;
        float cropRight = mCenterX + mCircleRadius;
        float cropBottom = mCenterY + mCircleRadius;

        // 1. 最小缩放保护：禁止缩小到圆圈外露黑边
        float minDim = mCircleRadius * 2.0f;
        if (r.width() < minDim || r.height() < minDim) {
            float scaleFix = Math.max(minDim / r.width(), minDim / r.height());
            mCurrentMatrix.postScale(scaleFix, scaleFix, mCenterX, mCenterY);
            r = new RectF(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
            mCurrentMatrix.mapRect(r);
        }

        // 2. 最大缩放保护 (限制最大 5 倍放大)
        float baseScale = getBaseMinScale();
        if (getMatrixScale() > baseScale * MAX_SCALE) {
            float maxFix = (baseScale * MAX_SCALE) / getMatrixScale();
            mCurrentMatrix.postScale(maxFix, maxFix, mCenterX, mCenterY);
            r = new RectF(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
            mCurrentMatrix.mapRect(r);
        }

        // 3. 边界贴合阻尼与自动回弹纠偏 (平移不可让图片脱离圆圈取景窗)
        float deltaX = 0f;
        float deltaY = 0f;

        if (r.left > cropLeft) {
            deltaX = cropLeft - r.left;
        } else if (r.right < cropRight) {
            deltaX = cropRight - r.right;
        }

        if (r.top > cropTop) {
            deltaY = cropTop - r.top;
        } else if (r.bottom < cropBottom) {
            deltaY = cropBottom - r.bottom;
        }

        mCurrentMatrix.postTranslate(deltaX, deltaY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mBitmap == null) return super.onTouchEvent(event);

        mScaleDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                mIsDragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mScaleDetector.isInProgress() && mIsDragging) {
                    float dx = event.getX() - mLastTouchX;
                    float dy = event.getY() - mLastTouchY;
                    mCurrentMatrix.postTranslate(dx, dy);
                    checkBoundsAndScale();
                    invalidate();
                }
                mLastTouchX = event.getX();
                mLastTouchY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsDragging = false;
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mBitmap != null && !mBitmap.isRecycled()) {
            canvas.drawBitmap(mBitmap, mCurrentMatrix, null);
        }
        // 绘制 EVEN_ODD 取景遮罩与圆形白色边缘
        canvas.drawPath(mMaskPath, mMaskPaint);
        canvas.drawCircle(mCenterX, mCenterY, mCircleRadius, mCircleStrokePaint);
    }

    /**
     * 按照用户所视即所得的圆形窗口，通过逆矩阵几何变换精确裁切无损 512x512 高清位图
     */
    public Bitmap cropBitmap() {
        if (mBitmap == null || mBitmap.isRecycled()) return null;

        Matrix inverse = new Matrix();
        mCurrentMatrix.invert(inverse);

        RectF cropWindow = new RectF(
                mCenterX - mCircleRadius,
                mCenterY - mCircleRadius,
                mCenterX + mCircleRadius,
                mCenterY + mCircleRadius
        );
        inverse.mapRect(cropWindow);

        int cropX = Math.max(0, Math.round(cropWindow.left));
        int cropY = Math.max(0, Math.round(cropWindow.top));
        int cropW = Math.min(Math.round(cropWindow.width()), mBitmap.getWidth() - cropX);
        int cropH = Math.min(Math.round(cropWindow.height()), mBitmap.getHeight() - cropY);

        int size = Math.min(cropW, cropH);
        if (size <= 0) return null;

        Bitmap cropped = Bitmap.createBitmap(mBitmap, cropX, cropY, size, size);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true);
        if (scaled != cropped) {
            cropped.recycle();
        }
        return scaled;
    }

    private float dp2px(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
