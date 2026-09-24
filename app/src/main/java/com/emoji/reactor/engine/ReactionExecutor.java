package com.emoji.reactor.engine;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.Toast;

import com.emoji.reactor.hook.QQKernelProbe;
import com.emoji.reactor.model.EmojiGroup;
import com.emoji.reactor.util.AppLogger;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量贴表情核心调度引擎（工业级健壮版）
 * 严格限制单次最多贴 20 个表情，支持禁言熔断保护与精准异步计数
 */
public class ReactionExecutor {

    private static final String TAG = "QQEmojiReactor_Exec";
    public static final int MAX_EMOJI_LIMIT = 20;          // QQ 官方单条消息表情回应最大上限
    public static final int ERR_EMOJI_ALREADY_SET = 65002; // QQ NT 已经贴过该表情的状态码

    private static final HandlerThread workerThread;
    private static final Handler workerHandler;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    static {
        workerThread = new HandlerThread("EmojiReactor-Worker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    /**
     * 触发批量贴表情任务
     *
     * @param context   上下文
     * @param msgRecord 选中的消息对象
     * @param group     待执行的方案
     */
    public static void executeBatchReaction(Context context, Object msgRecord, EmojiGroup group) {
        if (group == null || msgRecord == null) {
            return;
        }

        if (isRunning.get()) {
            showToast(context, "正在执行上一轮贴表情，请稍候...");
            return;
        }

        workerHandler.post(() -> {
            isRunning.set(true);
            try {
                List<com.emoji.reactor.model.GroupEmojiItem> rawItems = group.getItems();
                // 1. 防取消过滤：仅剔除本人已贴过的表情（通用 ID 去重）
                List<com.emoji.reactor.model.GroupEmojiItem> itemsToApply = ReactionDeduplicator.filterItemsToApply(msgRecord, rawItems);

                // 2. 严格遵循 QQ 上限 20 个表情
                if (itemsToApply.size() > MAX_EMOJI_LIMIT) {
                    itemsToApply = itemsToApply.subList(0, MAX_EMOJI_LIMIT);
                }

                if (itemsToApply.isEmpty()) {
                    showToast(context, "当前消息您已全部贴过该方案表情");
                    return;
                }

                showToast(context, "开始贴表情 (共 " + itemsToApply.size() + " 个)...");

                int delay = group.getDelayMs();
                if (delay < 50) delay = 50;

                final CountDownLatch latch = new CountDownLatch(itemsToApply.size());
                final AtomicInteger realSuccessCount = new AtomicInteger(0);
                final AtomicInteger alreadySetCount = new AtomicInteger(0);
                final AtomicBoolean isMuted = new AtomicBoolean(false);

                for (int i = 0; i < itemsToApply.size(); i++) {
                    if (isMuted.get()) {
                        // 禁言熔断保护：不再发出后续请求
                        latch.countDown();
                        continue;
                    }

                    com.emoji.reactor.model.GroupEmojiItem item = itemsToApply.get(i);
                    final String emojiId = item.getEmojiId();
                    final long emojiType = item.getEmojiType();

                    try {
                        QQKernelProbe.invokeSetMsgEmojiLike(msgRecord, emojiId, emojiType, (code, err) -> {
                            try {
                                if (code == 0) {
                                    realSuccessCount.incrementAndGet();
                                } else if (code == ERR_EMOJI_ALREADY_SET) {
                                    alreadySetCount.incrementAndGet();
                                } else if (code == 1001 || (err != null && (err.contains("禁言") || err.contains("mute") || err.contains("permission")))) {
                                    isMuted.set(true);
                                    AppLogger.e(TAG, "检测到禁言或无权限，触发熔断: " + err, null);
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
                    } catch (Throwable t) {
                        AppLogger.e(TAG, "单个表情发送异常 (ID: " + emojiId + ", Type: " + emojiType + ")", t);
                        latch.countDown();
                    }

                    // 两次调用之间的安全间隔休眠（平滑有序发送）
                    if (i < itemsToApply.size() - 1) {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }

                // 3. 确定性等待 JNI 异步回调落地（超时看门狗保护）
                long maxWaitMs = (long) delay * itemsToApply.size() + 1500L;
                try {
                    latch.await(maxWaitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }

                if (isMuted.get()) {
                    showToast(context, "提示：您在当前群已被禁言或无权限贴表情");
                    return;
                }

                int success = realSuccessCount.get();
                int already = alreadySetCount.get();

                if (success > 0 && already > 0) {
                    showToast(context, "完成！新贴 " + success + " 个 (" + already + " 个此前已贴)");
                } else if (success > 0) {
                    showToast(context, "完成！成功贴上 " + success + " 个表情");
                } else if (already > 0) {
                    showToast(context, "提示：这几个表情此前您已全部贴过");
                } else {
                    showToast(context, "贴表情请求已全部发出");
                }

            } catch (Throwable t) {
                AppLogger.e(TAG, "批量贴表情流程异常", t);
                showToast(context, "贴表情异常: " + t.getMessage());
            } finally {
                isRunning.set(false);
            }
        });
    }

    private static void showToast(Context context, String text) {
        if (context == null) return;
        mainHandler.post(() -> {
            try {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }
}
