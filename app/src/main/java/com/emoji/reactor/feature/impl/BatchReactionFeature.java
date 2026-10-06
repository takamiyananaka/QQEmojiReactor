package com.emoji.reactor.feature.impl;

import com.emoji.reactor.R;
import com.emoji.reactor.feature.BaseFeature;
import com.emoji.reactor.hook.MessageMenuHook;
import com.emoji.reactor.hook.QQDynamicEmojiDumper;
import com.emoji.reactor.hook.QQKernelProbe;

/**
 * 批量贴表情核心功能特征 (BatchReactionFeature)
 */
public class BatchReactionFeature extends BaseFeature {

    public static final String KEY = "feature_batch_reaction";

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public int getTitleRes() {
        return R.string.feature_reaction_title;
    }

    @Override
    public int getDescRes() {
        return R.string.feature_reaction_desc;
    }

    @Override
    public boolean isDefaultEnabled() {
        return true;
    }

    @Override
    public void onInit(ClassLoader cl) throws Throwable {
        MessageMenuHook.init(cl);
        QQKernelProbe.initProbe(cl);
        QQDynamicEmojiDumper.init(cl);
    }
}
