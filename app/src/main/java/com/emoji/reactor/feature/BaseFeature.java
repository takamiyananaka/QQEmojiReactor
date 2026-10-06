package com.emoji.reactor.feature;

/**
 * 功能特征抽象基类 (BaseFeature)
 * 遵循开闭原则 (OCP) 与单一职责原则 (SRP)
 * 每个功能拥有独立配置标识、生命周期与异常沙箱
 */
public abstract class BaseFeature {

    /**
     * 唯一功能特征标识符 (例如 "feature_batch_reaction", "feature_flash_pic", "feature_anti_recall")
     */
    public abstract String getKey();

    /**
     * 功能标题资源 ID
     */
    public abstract int getTitleRes();

    /**
     * 功能描述资源 ID
     */
    public abstract int getDescRes();

    /**
     * 默认开启状态 (默认 true)
     */
    public boolean isDefaultEnabled() {
        return true;
    }

    /**
     * 宿主 Hook 初始化核心逻辑
     *
     * @param cl 宿主 QQ 核心 ClassLoader
     * @throws Throwable 发生任何反射或 JNI 异常均会被 FeatureManager 安全捕获并隔离
     */
    public abstract void onInit(ClassLoader cl) throws Throwable;
}
