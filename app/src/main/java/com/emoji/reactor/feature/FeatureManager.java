package com.emoji.reactor.feature;

import android.content.Context;

import com.emoji.reactor.feature.impl.AntiRecallFeature;
import com.emoji.reactor.feature.impl.BatchReactionFeature;
import com.emoji.reactor.feature.impl.CustomAvatarFeature;
import com.emoji.reactor.feature.impl.FlashPicFeature;
import com.emoji.reactor.hook.RemoteConfigHelper;
import com.emoji.reactor.util.AppLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模块功能特征统一调度管理器 (FeatureManager)
 * 具备独立异常隔离熔断机制：单一功能崩溃或版本变动绝不波及宿主 QQ 或其他功能
 */
public class FeatureManager {

    private static final String TAG = "FeatureManager";
    private static final List<BaseFeature> features = new ArrayList<>();

    static {
        // 注册所有业务功能特征
        features.add(new BatchReactionFeature());
        features.add(new FlashPicFeature());
        features.add(new AntiRecallFeature());
        features.add(new CustomAvatarFeature());
    }

    public static List<BaseFeature> getAllFeatures() {
        return Collections.unmodifiableList(features);
    }

    /**
     * 在 QQ 聊天主进程中全量挂载所有功能特征切面探针
     * 运行时由各个功能内部的 isEnabledRuntime() 原子守卫动态控制放行，确保 100% 免重启热启停
     */
    public static void initAllFeatures(Context context, ClassLoader cl) {
        if (cl == null) return;

        AppLogger.i(TAG, "开始分发初始化所有插件功能特征 (共 " + features.size() + " 个)...");

        for (BaseFeature feature : features) {
            String key = feature.getKey();
            try {
                feature.onInit(cl);
                AppLogger.i(TAG, "功能 [" + key + "] 探针已成功挂载就绪！");
            } catch (Throwable t) {
                // 工业级容错：单功能初始化异常绝不外抛，保证宿主 QQ 绝对安全
                AppLogger.e(TAG, "功能 [" + key + "] 初始化异常，已安全隔离熔断: " + t.getMessage(), t);
            }
        }
    }
}
