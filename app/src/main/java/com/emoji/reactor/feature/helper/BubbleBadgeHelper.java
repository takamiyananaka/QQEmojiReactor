package com.emoji.reactor.feature.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.emoji.reactor.feature.impl.AntiRecallFeature;
import com.emoji.reactor.feature.impl.FlashPicFeature;
import com.emoji.reactor.hook.RemoteConfigHelper;
import com.emoji.reactor.util.AppLogger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 气泡与图片视觉徽标管理器 (BubbleBadgeHelper)
 * 严格遵循用户界面设计与工业级自适应规范：
 * 1. 闪照标注：在图片右上角位置，透明底红色小字，纯文字【闪照】（无 Emoji，不遮挡画面主体）
 * 2. 撤回标注：在消息最后、气泡正右上方，透明底红色小字，纯文字【已撤回】（随文本长短/图片尺寸动态自适应，告别误锁昵称的太高问题）
 * 3. 动态弱引用池管理，开关变更毫秒级响应，零排版污染、零内存泄漏
 */
public class BubbleBadgeHelper {

    private static final String TAG = "BubbleBadgeHelper";

    public static final int ID_BADGE_CONTAINER = 0x7E110030;
    public static final int ID_RECALL_BADGE = 0x7E110031;
    public static final int ID_FLASH_BADGE = 0x7E110032;
    public static final int ID_RECALL_BORDER = 0x7E110033;

    // ConstraintSet 核心常量
    private static final int CS_TOP = 3;
    private static final int CS_BOTTOM = 4;
    private static final int CS_START = 6;
    private static final int CS_END = 7;

    // 弱引用视图池：记录当前屏幕上活跃绑定的根视图与对应的 msgSeq
    private static final Map<ViewGroup, Long> activeBubbleViews =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ViewGroup, LayoutInfo> activeLayoutInfos =
            Collections.synchronizedMap(new WeakHashMap<>());

    // 真实内容宿主映射表：通过 WeakReference 解除强引用闭环，彻底杜绝 View 树内存泄漏
    private static final Map<ViewGroup, java.lang.ref.WeakReference<View>> rootToBubbleChild =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static class LayoutInfo {
        final boolean isGroup;
        final boolean isSelf;

        LayoutInfo(boolean isGroup, boolean isSelf) {
            this.isGroup = isGroup;
            this.isSelf = isSelf;
        }
    }

    public static void hideBadges(ViewGroup rootView) {
        if (rootView == null) return;
        View flash = rootView.findViewById(ID_FLASH_BADGE);
        if (flash != null) flash.setVisibility(View.GONE);
        View recall = rootView.findViewById(ID_RECALL_BADGE);
        if (recall != null) recall.setVisibility(View.GONE);
        View border = rootView.findViewById(ID_RECALL_BORDER);
        if (border != null) border.setVisibility(View.GONE);
        View oldContainer = rootView.findViewById(ID_BADGE_CONTAINER);
        if (oldContainer != null) oldContainer.setVisibility(View.GONE);
        java.lang.ref.WeakReference<View> ref = rootToBubbleChild.get(rootView);
        View bubbleChild = ref != null ? ref.get() : null;
        if (bubbleChild != null) {
            removeRedBorder(bubbleChild);
        } else {
            for (int i = 0; i < rootView.getChildCount(); i++) {
                removeRedBorder(rootView.getChildAt(i));
            }
        }
    }

    private static void applyRedBorder(Context ctx, View bubbleChild) {
        if (bubbleChild == null) return;
        try {
            android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
            border.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            border.setColor(Color.TRANSPARENT);
            border.setStroke(dp2px(ctx, 1.5f), Color.parseColor("#E53935"));
            border.setCornerRadius(dp2px(ctx, 12f));
            bubbleChild.setForeground(border);
        } catch (Throwable t) {
            AppLogger.d(TAG, "applyRedBorder 异常: " + t.getMessage());
        }
    }

    private static void removeRedBorder(View bubbleChild) {
        if (bubbleChild == null) return;
        try {
            bubbleChild.setForeground(null);
        } catch (Exception e) {
            AppLogger.d(TAG, "removeRedBorder 安全跳过: " + e.getMessage());
        }
    }

    /**
     * 来自 BaseContentComponent#a2 的高精度绑定入口：传递真正的宿主组件与直接子容器
     */
    public static void onBindComponentView(ViewGroup rootView, View bubbleChild, View compView, Object msgRecord) {
        if (rootView == null || msgRecord == null) return;
        if (bubbleChild != null) {
            rootToBubbleChild.put(rootView, new java.lang.ref.WeakReference<>(bubbleChild));
        }
        onBindMessageView(rootView, msgRecord);
    }

    /**
     * 绑定气泡视图时渲染徽标 (在 AIOBubbleMsgItemVB.P 或 handleUIState 中调用)
     */
    public static void onBindMessageView(ViewGroup rootView, Object msgRecord) {
        if (rootView == null || msgRecord == null) return;

        try {
            Context ctx = rootView.getContext();
            boolean moduleOn = RemoteConfigHelper.isModuleEnabled(ctx);
            boolean recallOn = moduleOn && RemoteConfigHelper.isFeatureEnabled(ctx, AntiRecallFeature.KEY, true);
            boolean flashOn = moduleOn && RemoteConfigHelper.isFeatureEnabled(ctx, FlashPicFeature.KEY, true);

            if (!recallOn && !flashOn) {
                hideBadges(rootView);
                return;
            }

            long msgSeq = 0L;
            try {
                Field fSeq = msgRecord.getClass().getDeclaredField("msgSeq");
                fSeq.setAccessible(true);
                msgSeq = fSeq.getLong(msgRecord);
            } catch (Exception e) {
                AppLogger.d(TAG, "反射读取msgSeq安全跳过: " + e.getMessage());
            }

            if (msgSeq <= 0) return;

            // 1. 提取会话与消息方向属性
            boolean isGroup = false;
            boolean isSelf = false;
            try {
                Field fChatType = msgRecord.getClass().getDeclaredField("chatType");
                fChatType.setAccessible(true);
                isGroup = (fChatType.getInt(msgRecord) == 2);
            } catch (Exception e) {
                AppLogger.d(TAG, "反射读取chatType安全跳过: " + e.getMessage());
            }

            try {
                Field fSendType = msgRecord.getClass().getDeclaredField("sendType");
                fSendType.setAccessible(true);
                isSelf = (fSendType.getInt(msgRecord) == 1);
            } catch (Exception e) {
                AppLogger.d(TAG, "反射读取sendType安全跳过: " + e.getMessage());
            }

            // 2. 检查本地撤回时间戳 (MsgRecord.recallTime > 0)
            long recallTime = 0L;
            try {
                Field fRecall = msgRecord.getClass().getDeclaredField("recallTime");
                fRecall.setAccessible(true);
                recallTime = fRecall.getLong(msgRecord);
            } catch (Exception e) {
                try {
                    Method mRecall = msgRecord.getClass().getMethod("getRecallTime");
                    Object val = mRecall.invoke(msgRecord);
                    if (val instanceof Number) {
                        recallTime = ((Number) val).longValue();
                    }
                } catch (Exception e2) {
                    AppLogger.d(TAG, "反射调用getRecallTime安全跳过: " + e2.getMessage());
                }
            }

            boolean isRecalled = recallOn && ((recallTime > 0) || AntiRecallFeature.isRecalled(msgSeq));
            if (recallTime > 0 && recallOn) {
                AntiRecallFeature.recalledMsgSeqs.add(msgSeq);
            }

            boolean isFlash = flashOn && FlashPicFeature.isFlashPic(msgRecord, msgSeq);

            activeBubbleViews.put(rootView, msgSeq);
            activeLayoutInfos.put(rootView, new LayoutInfo(isGroup, isSelf));

            renderBadges(rootView, isRecalled, isFlash, isGroup, isSelf);

        } catch (Throwable t) {
            AppLogger.e(TAG, "onBindMessageView 异常", t);
        }
    }

    /**
     * 当收到网络撤回推送时，实时秒级点亮当前屏幕上可见的对应气泡红标
     */
    public static void notifyMessageRecalledRealtime(long targetSeq) {
        boolean moduleOn = RemoteConfigHelper.isModuleEnabled();
        boolean recallOn = moduleOn && RemoteConfigHelper.isFeatureEnabled(AntiRecallFeature.KEY, true);
        if (!recallOn) return;

        synchronized (activeBubbleViews) {
            for (Map.Entry<ViewGroup, Long> entry : activeBubbleViews.entrySet()) {
                if (entry.getValue() != null && entry.getValue() == targetSeq) {
                    ViewGroup rootView = entry.getKey();
                    if (rootView != null) {
                        rootView.post(() -> {
                            try {
                                LayoutInfo info = activeLayoutInfos.get(rootView);
                                boolean isGroup = info != null && info.isGroup;
                                boolean isSelf = info != null && info.isSelf;
                                boolean isFlash = FlashPicFeature.isFlashPicBySeq(targetSeq);
                                renderBadges(rootView, true, isFlash, isGroup, isSelf);
                            } catch (Exception e) {
                                AppLogger.d(TAG, "notifyMessageRecalledRealtime 渲染异常安全跳过: " + e.getMessage());
                            }
                        });
                    }
                }
            }
        }
    }

    /**
     * 收到配置广播变更时，秒级刷新当前屏幕所有已绑定的气泡徽标（开关关闭即刻隐形，开关开启即刻点亮）
     */
    public static void refreshAllVisibleBadges() {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> {
            boolean moduleOn = RemoteConfigHelper.isModuleEnabled();
            boolean recallOn = moduleOn && RemoteConfigHelper.isFeatureEnabled(AntiRecallFeature.KEY, true);
            boolean flashOn = moduleOn && RemoteConfigHelper.isFeatureEnabled(FlashPicFeature.KEY, true);

            synchronized (activeBubbleViews) {
                for (Map.Entry<ViewGroup, Long> entry : activeBubbleViews.entrySet()) {
                    ViewGroup rootView = entry.getKey();
                    Long msgSeq = entry.getValue();
                    if (rootView != null) {
                        if (!recallOn && !flashOn) {
                            hideBadges(rootView);
                        } else if (msgSeq != null && msgSeq > 0) {
                            LayoutInfo info = activeLayoutInfos.get(rootView);
                            boolean isGroup = info != null && info.isGroup;
                            boolean isSelf = info != null && info.isSelf;
                            boolean isRecalled = recallOn && AntiRecallFeature.isRecalled(msgSeq);
                            boolean isFlash = flashOn && FlashPicFeature.isFlashPicBySeq(msgSeq);
                            renderBadges(rootView, isRecalled, isFlash, isGroup, isSelf);
                        }
                    }
                }
            }
        });
    }

    private static void renderBadges(ViewGroup rootView, boolean isRecalled, boolean isFlash,
                                     boolean isGroup, boolean isSelf) {
        if (rootView == null) return;

        View bubbleChild = findBubbleChild(rootView);
        if (bubbleChild == null) {
            hideBadges(rootView);
            return;
        }

        if (!isRecalled && !isFlash) {
            hideBadges(rootView);
            return;
        }

        // 防裁切防御：确保气泡外部正上方的红色小字不被父容器截断
        try {
            rootView.setClipChildren(false);
            rootView.setClipToPadding(false);
        } catch (Exception e) {
            AppLogger.d(TAG, "setClipChildren 安全跳过: " + e.getMessage());
        }

        Context ctx = rootView.getContext();

        // 1. [闪照] 透明底红色小字（严格贴在图片右上角位置）
        TextView tvFlash = rootView.findViewById(ID_FLASH_BADGE);
        if (isFlash) {
            if (tvFlash == null) {
                tvFlash = createBadgeTextView(ctx, ID_FLASH_BADGE, "闪照");
                rootView.addView(tvFlash);
            }
            tvFlash.setVisibility(View.VISIBLE);
        } else if (tvFlash != null) {
            tvFlash.setVisibility(View.GONE);
        }

        // 2. [已撤回] 透明底红色小字（该条消息最后，气泡正右上方）
        TextView tvRecall = rootView.findViewById(ID_RECALL_BADGE);
        View borderOverlay = rootView.findViewById(ID_RECALL_BORDER);
        if (isRecalled) {
            if (tvRecall == null) {
                tvRecall = createBadgeTextView(ctx, ID_RECALL_BADGE, "已撤回");
                rootView.addView(tvRecall);
            }
            tvRecall.setVisibility(View.VISIBLE);

            if (borderOverlay == null) {
                borderOverlay = new View(ctx);
                borderOverlay.setId(ID_RECALL_BORDER);
                borderOverlay.setClickable(false);
                borderOverlay.setFocusable(false);
                android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
                border.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                border.setColor(Color.TRANSPARENT);
                border.setStroke(dp2px(ctx, 1.5f), Color.parseColor("#E53935"));
                border.setCornerRadius(dp2px(ctx, 12f));
                borderOverlay.setBackground(border);
                rootView.addView(borderOverlay);
            }
            borderOverlay.setVisibility(View.VISIBLE);

            applyRedBorder(ctx, bubbleChild);
        } else {
            if (tvRecall != null) {
                tvRecall.setVisibility(View.GONE);
            }
            if (borderOverlay != null) {
                borderOverlay.setVisibility(View.GONE);
            }
            removeRedBorder(bubbleChild);
        }

        applyConstraints(rootView, bubbleChild, tvFlash, isFlash, tvRecall, borderOverlay, isRecalled);
    }

    private static TextView createBadgeTextView(Context ctx, int id, String text) {
        TextView tv = new TextView(ctx);
        tv.setId(id);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#E53935")); // 醒目官方警示红
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setIncludeFontPadding(false);
        tv.setBackground(null); // 100% 透明底
        return tv;
    }

    /**
     * 运用 ConstraintLayout 原生算法分别精准定位【闪照】与【已撤回】
     * 锚点严格绑定在真实气泡直接子容器 (bubbleChild) 上，彻底杜绝误锁头像或昵称
     */
    private static void applyConstraints(ViewGroup rootView, View bubbleChild,
                                         View tvFlash, boolean isFlash,
                                         View tvRecall, View borderOverlay, boolean isRecalled) {
        if (bubbleChild == null || rootView == null) return;

        if (bubbleChild.getId() == View.NO_ID) {
            bubbleChild.setId(View.generateViewId());
        }

        int bubbleId = bubbleChild.getId();
        Context ctx = rootView.getContext();

        AppLogger.i(TAG, "【动态气泡锚定】锁定气泡类名: " + bubbleChild.getClass().getName() + ", bubbleId=" + bubbleId + ", 撤回=" + isRecalled + ", 闪照=" + isFlash);

        // 1. 设置原生 LayoutParams 作为坚固底线
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> lpClz = null;
            try {
                lpClz = cl != null ? cl.loadClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams") : null;
            } catch (Exception e) {
                AppLogger.d(TAG, "加载ConstraintLayout$LayoutParams安全跳过: " + e.getMessage());
            }
            if (lpClz == null) {
                lpClz = Class.forName("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", false, BubbleBadgeHelper.class.getClassLoader());
            }

            Constructor<?> ctor = lpClz.getConstructor(int.class, int.class);

            if (isRecalled && tvRecall != null) {
                ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) ctor.newInstance(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lpClz.getField("topToTop").set(lp, bubbleId);
                lpClz.getField("endToEnd").set(lp, bubbleId);
                ViewGroup.MarginLayoutParams.class.getField("topMargin").setInt(lp, dp2px(ctx, 3f));
                ViewGroup.MarginLayoutParams.class.getField("rightMargin").setInt(lp, dp2px(ctx, 6f));
                tvRecall.setLayoutParams(lp);
            }

            if (isFlash && tvFlash != null) {
                ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) ctor.newInstance(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lpClz.getField("topToTop").set(lp, bubbleId);
                if (isRecalled && tvRecall != null) {
                    lpClz.getField("endToStart").set(lp, ID_RECALL_BADGE);
                    ViewGroup.MarginLayoutParams.class.getField("rightMargin").setInt(lp, dp2px(ctx, 4f));
                } else {
                    lpClz.getField("endToEnd").set(lp, bubbleId);
                    ViewGroup.MarginLayoutParams.class.getField("rightMargin").setInt(lp, dp2px(ctx, 6f));
                }
                ViewGroup.MarginLayoutParams.class.getField("topMargin").setInt(lp, dp2px(ctx, 3f));
                tvFlash.setLayoutParams(lp);
            }

            if (isRecalled && borderOverlay != null) {
                ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) ctor.newInstance(0, 0);
                lpClz.getField("topToTop").set(lp, bubbleId);
                lpClz.getField("bottomToBottom").set(lp, bubbleId);
                lpClz.getField("startToStart").set(lp, bubbleId);
                lpClz.getField("endToEnd").set(lp, bubbleId);
                borderOverlay.setLayoutParams(lp);
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "设置LayoutParams兜底安全跳过: " + e.getMessage());
        }

        // 2. 运用 ConstraintSet.clone + connect + applyTo 驱动 ConstraintLayout 动态刷新
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> csClz = null;
            try {
                csClz = cl != null ? cl.loadClass("androidx.constraintlayout.widget.ConstraintSet") : null;
            } catch (Exception e) {
                AppLogger.d(TAG, "加载ConstraintSet安全跳过: " + e.getMessage());
            }
            if (csClz == null) {
                csClz = Class.forName("androidx.constraintlayout.widget.ConstraintSet", false, BubbleBadgeHelper.class.getClassLoader());
            }

            Class<?> clClz = null;
            try {
                clClz = cl != null ? cl.loadClass("androidx.constraintlayout.widget.ConstraintLayout") : null;
            } catch (Exception e) {
                AppLogger.d(TAG, "加载ConstraintLayout安全跳过: " + e.getMessage());
            }
            if (clClz == null) {
                clClz = Class.forName("androidx.constraintlayout.widget.ConstraintLayout", false, BubbleBadgeHelper.class.getClassLoader());
            }

            Object cs = csClz.newInstance();
            Method mClone = csClz.getMethod("clone", clClz);
            mClone.invoke(cs, rootView);

            Method mConnect5 = csClz.getMethod("connect", int.class, int.class, int.class, int.class, int.class);
            Method mClear = csClz.getMethod("clear", int.class, int.class);
            Method mApplyTo = csClz.getMethod("applyTo", clClz);

            // 1. 已撤回位置约束：严格落在气泡/消息本体上方内侧 (topToTop & endToEnd)，坚决不越界跑到名字上！
            if (isRecalled && tvRecall != null) {
                mClear.invoke(cs, ID_RECALL_BADGE, CS_START);
                mClear.invoke(cs, ID_RECALL_BADGE, CS_END);
                mClear.invoke(cs, ID_RECALL_BADGE, CS_TOP);
                mClear.invoke(cs, ID_RECALL_BADGE, CS_BOTTOM);

                mConnect5.invoke(cs, ID_RECALL_BADGE, CS_TOP, bubbleId, CS_TOP, dp2px(ctx, 3f));
                mConnect5.invoke(cs, ID_RECALL_BADGE, CS_END, bubbleId, CS_END, dp2px(ctx, 6f));
            }

            // 2. 闪照位置约束：若有已撤回则排在已撤回左侧，若无则贴在右上角
            if (isFlash && tvFlash != null) {
                mClear.invoke(cs, ID_FLASH_BADGE, CS_START);
                mClear.invoke(cs, ID_FLASH_BADGE, CS_END);
                mClear.invoke(cs, ID_FLASH_BADGE, CS_TOP);
                mClear.invoke(cs, ID_FLASH_BADGE, CS_BOTTOM);

                mConnect5.invoke(cs, ID_FLASH_BADGE, CS_TOP, bubbleId, CS_TOP, dp2px(ctx, 3f));
                if (isRecalled && tvRecall != null) {
                    mConnect5.invoke(cs, ID_FLASH_BADGE, CS_END, ID_RECALL_BADGE, CS_START, dp2px(ctx, 4f));
                } else {
                    mConnect5.invoke(cs, ID_FLASH_BADGE, CS_END, bubbleId, CS_END, dp2px(ctx, 6f));
                }
            }

            // 3. 红线外框约束：完美重叠贴合在 bubbleChild 的 4 个边缘 (width=0, height=0 MATCH_CONSTRAINT)
            if (isRecalled && borderOverlay != null) {
                mClear.invoke(cs, ID_RECALL_BORDER, CS_START);
                mClear.invoke(cs, ID_RECALL_BORDER, CS_END);
                mClear.invoke(cs, ID_RECALL_BORDER, CS_TOP);
                mClear.invoke(cs, ID_RECALL_BORDER, CS_BOTTOM);

                mConnect5.invoke(cs, ID_RECALL_BORDER, CS_START, bubbleId, CS_START, 0);
                mConnect5.invoke(cs, ID_RECALL_BORDER, CS_END, bubbleId, CS_END, 0);
                mConnect5.invoke(cs, ID_RECALL_BORDER, CS_TOP, bubbleId, CS_TOP, 0);
                mConnect5.invoke(cs, ID_RECALL_BORDER, CS_BOTTOM, bubbleId, CS_BOTTOM, 0);
            }

            mApplyTo.invoke(cs, rootView);
        } catch (Throwable t) {
            AppLogger.d(TAG, "ConstraintSet.applyTo 降级完成: " + t.getMessage());
        }
    }

    /**
     * 精确获取当前消息气泡/图片在 rootView 下的直接子 View
     * 严守强类型断言，彻底排除头像 (head_icon) 与群昵称 (nick_name)，锁定真实气泡
     */
    private static View findBubbleChild(ViewGroup root) {
        if (root == null) return null;

        // 策略 1: 优先使用来自 BaseContentComponent (A1/R0/V0) 动态回溯锁定的 100% 精确映射
        java.lang.ref.WeakReference<View> ref = rootToBubbleChild.get(root);
        View cached = ref != null ? ref.get() : null;
        if (cached != null && cached.getParent() == root) {
            return cached;
        }

        // 策略 2: 扫描官方专属气泡类名 BubbleLayoutCompatPress (TimTool / TCQT / QFun 权威标准)
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            String clsName = child.getClass().getName();
            if (clsName.contains("BubbleLayoutCompatPress") || clsName.contains("bubble.BubbleLayout")) {
                return child;
            }
        }

        // 策略 3: 递归扫描子树，查找内部包裹 BubbleLayoutCompatPress 的直接子容器
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getId() == ID_FLASH_BADGE || child.getId() == ID_RECALL_BADGE || child.getId() == ID_BADGE_CONTAINER) {
                continue;
            }
            if (child instanceof ViewGroup) {
                View inner = findBubbleViewRecursive((ViewGroup) child, 3);
                if (inner != null) {
                    return child;
                }
            }
        }

        // 策略 4: 拓扑结构过滤：排除时间戳、昵称与头像，锁定消息内容主体容器
        int avatarMaxPx = dp2px(root.getContext(), 56f);
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getId() == ID_FLASH_BADGE || child.getId() == ID_RECALL_BADGE || child.getId() == ID_BADGE_CONTAINER) {
                continue;
            }
            if (!(child instanceof ViewGroup)) continue;

            ViewGroup vg = (ViewGroup) child;
            ViewGroup.LayoutParams lp = vg.getLayoutParams();
            // 头像为固定 <= 56dp 的小方块，彻底排除
            if (lp != null && lp.width > 0 && lp.width <= avatarMaxPx && lp.height > 0 && lp.height <= avatarMaxPx) {
                continue;
            }

            // 检查子树：气泡容器内部必定包含至少 2 层嵌套与内容 View
            if (vg.getChildCount() > 0 && hasMessageContent(vg)) {
                return child;
            }
        }
        return null;
    }

    private static boolean hasMessageContent(ViewGroup vg) {
        if (vg == null) return false;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof ViewGroup && ((ViewGroup) c).getChildCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private static View findBubbleViewRecursive(ViewGroup parent, int depth) {
        if (parent == null || depth <= 0) return null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            String name = child.getClass().getName();
            if (name.contains("BubbleLayoutCompatPress") || name.contains("bubble.BubbleLayout")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View v = findBubbleViewRecursive((ViewGroup) child, depth - 1);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static int dp2px(Context context, float dp) {
        if (context == null) return (int) dp;
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
