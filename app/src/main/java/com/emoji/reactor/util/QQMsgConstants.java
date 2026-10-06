package com.emoji.reactor.util;

/**
 * QQ 协议层业务消息常量 (QQMsgConstants)
 * 集中管理防撤回、闪照等逆向协议魔数，杜绝硬编码
 */
public final class QQMsgConstants {

    private QQMsgConstants() {
    }

    // 防撤回协议常量
    public static final int MSG_TYPE_GROUP_RECALL = 732;
    public static final int SUB_TYPE_GROUP_RECALL = 17;
    public static final int MSG_TYPE_C2C_RECALL = 528;
    public static final int SUB_TYPE_C2C_RECALL = 138;

    // 闪照无痕破解常量
    public static final int FLASH_PIC_SUB_TYPE_MASK = 8192;
    public static final int FLASH_PIC_FLAG_MASK_1 = 8194;
    public static final int FLASH_PIC_FLAG_MASK_2 = 12288;
}
