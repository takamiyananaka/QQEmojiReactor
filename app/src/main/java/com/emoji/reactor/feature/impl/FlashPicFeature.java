package com.emoji.reactor.feature.impl;

import com.emoji.reactor.R;
import com.emoji.reactor.feature.BaseFeature;
import com.emoji.reactor.hook.RemoteConfigHelper;
import com.emoji.reactor.util.AppLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 闪照无痕破解功能特征 (FlashPicFeature)
 * 融合 QAuxiliary 与 TCQT 经过实战检验的成熟实现：
 * 1. 严格仅识别官方专有闪照特征：
 *    - PicElement.getIsFlashPic() == true
 *    - 或 MsgRecord.subMsgType 为 8194 (8192+2) 或 12288 (8192+4096)
 *    - 严禁触碰或中和普通图片的 picSubType（普通相册图/GIF 正常就是 1，绝不将其实施闪照识别）
 * 2. 掩码清零与布尔覆写：
 *    - 清除 MsgRecord.subMsgType 的闪照第13位掩码 8192 (0x2000)
 *    - 将 PicElement.isFlashPic 覆写为 false
 * 3. 破除 5 秒销毁限制，原生支持大图全屏查看与长按保存
 */
public class FlashPicFeature extends BaseFeature {

    public static final String KEY = "feature_flash_pic";
    private static final String TAG = "FlashPicFeature";
    private static final int FLASH_PIC_MASK = com.emoji.reactor.util.QQMsgConstants.FLASH_PIC_SUB_TYPE_MASK; // 0x2000
    private static final int MAX_TRACKED_SEQS = 4096;

    public static final Set<Long> flashPicMsgSeqs = createLruSet(MAX_TRACKED_SEQS);

    private static Set<Long> createLruSet(final int maxEntries) {
        Map<Long, Boolean> map = Collections.synchronizedMap(new LinkedHashMap<Long, Boolean>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > maxEntries;
            }
        });
        return Collections.newSetFromMap(map);
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public int getTitleRes() {
        return R.string.feature_flash_pic_title;
    }

    @Override
    public int getDescRes() {
        return R.string.feature_flash_pic_desc;
    }

    @Override
    public boolean isDefaultEnabled() {
        return true;
    }

    public static boolean isEnabledRuntime() {
        return RemoteConfigHelper.isModuleEnabled() && RemoteConfigHelper.isFeatureEnabled(KEY, true);
    }

    public static boolean isFlashPicBySeq(long msgSeq) {
        if (!isEnabledRuntime()) return false;
        return msgSeq > 0 && flashPicMsgSeqs.contains(msgSeq);
    }

    /**
     * 工业级双重闪照精确判定，杜绝误杀普通照片
     */
    public static boolean isFlashPic(Object msgRecord, long msgSeq) {
        if (!isEnabledRuntime()) return false;
        if (msgSeq > 0 && flashPicMsgSeqs.contains(msgSeq)) {
            return true;
        }
        if (msgRecord == null) return false;

        try {
            // 1. 检查 MsgRecord.subMsgType 是否为 8194 或 12288 (严格匹配 QAuxiliary / TCQT)
            Field fSub = msgRecord.getClass().getDeclaredField("subMsgType");
            fSub.setAccessible(true);
            int sub = fSub.getInt(msgRecord);
            if (sub == com.emoji.reactor.util.QQMsgConstants.FLASH_PIC_FLAG_MASK_1
                    || sub == com.emoji.reactor.util.QQMsgConstants.FLASH_PIC_FLAG_MASK_2) {
                if (msgSeq > 0) flashPicMsgSeqs.add(msgSeq);
                return true;
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "isFlashPicMsgRecord 检查subMsgType安全跳过: " + e.getMessage());
        }

        try {
            // 2. 检查 PicElement.isFlashPic 官方布尔字段
            List<?> elements = (List<?>) XposedHelpers.getObjectField(msgRecord, "elements");
            if (elements != null) {
                for (Object elem : elements) {
                    if (elem == null) continue;
                    Object picElem = XposedHelpers.getObjectField(elem, "picElement");
                    if (picElem != null) {
                        Field fFlash = picElem.getClass().getDeclaredField("isFlashPic");
                        fFlash.setAccessible(true);
                        Object val = fFlash.get(picElem);
                        if (Boolean.TRUE.equals(val)) {
                            if (msgSeq > 0) flashPicMsgSeqs.add(msgSeq);
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "isFlashPicMsgRecord 检查isFlashPic安全跳过: " + e.getMessage());
        }

        return false;
    }

    @Override
    public void onInit(ClassLoader cl) throws Throwable {
        hookMsgRecordSubMsgType(cl);
        hookAioMsgItem(cl);
        hookPicElement(cl);
        hookPicContentComponent(cl);
    }

    /**
     * 核心防线 1：拦截 MsgRecord#getSubMsgType() 掩码清零
     */
    private void hookMsgRecordSubMsgType(ClassLoader cl) {
        try {
            Class<?> msgRecordClass = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.kernel.nativeinterface.MsgRecord", cl);
            if (msgRecordClass == null) return;

            Method getSubMsgTypeMethod = XposedHelpers.findMethodExactIfExists(
                    msgRecordClass, "getSubMsgType");
            if (getSubMsgTypeMethod != null) {
                XposedBridge.hookMethod(getSubMsgTypeMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        Object result = param.getResult();
                        if (result instanceof Number) {
                            int subMsgType = ((Number) result).intValue();
                            if (subMsgType == com.emoji.reactor.util.QQMsgConstants.FLASH_PIC_FLAG_MASK_1
                                    || subMsgType == com.emoji.reactor.util.QQMsgConstants.FLASH_PIC_FLAG_MASK_2) {
                                try {
                                    long seq = XposedHelpers.getLongField(param.thisObject, "msgSeq");
                                    if (seq > 0) flashPicMsgSeqs.add(seq);
                                } catch (Exception e) {
                                    AppLogger.d(TAG, "getSubMsgType 读取msgSeq安全跳过: " + e.getMessage());
                                }
                                param.setResult(subMsgType & ~FLASH_PIC_MASK);
                            }
                        }
                    }
                });
                AppLogger.i(TAG, "已成功挂载 MsgRecord#getSubMsgType 闪照掩码清零探针！");
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookMsgRecordSubMsgType 异常", t);
        }
    }

    /**
     * 核心防线 2：拦截 AIOMsgItem#getMsgRecord() 清理字段并中和图片元素
     */
    private void hookAioMsgItem(ClassLoader cl) {
        try {
            Class<?> aioMsgItemClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.aio.msg.AIOMsgItem", cl);
            if (aioMsgItemClass == null) return;

            Method getMsgRecordMethod = XposedHelpers.findMethodExactIfExists(
                    aioMsgItemClass, "getMsgRecord");
            if (getMsgRecordMethod != null) {
                XposedBridge.hookMethod(getMsgRecordMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        Object msgRecord = param.getResult();
                        if (msgRecord != null) {
                            neutralizeMsgRecord(msgRecord);
                        }
                    }
                });
                AppLogger.i(TAG, "已成功挂载 AIOMsgItem#getMsgRecord 闪照捕获探针！");
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookAioMsgItem 异常", t);
        }
    }

    private void hookPicElement(ClassLoader cl) {
        try {
            Class<?> picElementClass = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.kernel.nativeinterface.PicElement", cl);
            if (picElementClass == null) return;

            Method getIsFlashPicMethod = XposedHelpers.findMethodExactIfExists(
                    picElementClass, "getIsFlashPic");
            if (getIsFlashPicMethod != null) {
                XposedBridge.hookMethod(getIsFlashPicMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        if (Boolean.TRUE.equals(param.getResult())) {
                            param.setResult(Boolean.FALSE);
                        }
                    }
                });
            }

            Method setIsFlashPicMethod = XposedHelpers.findMethodExactIfExists(
                    picElementClass, "setIsFlashPic", Boolean.class);
            if (setIsFlashPicMethod != null) {
                XposedBridge.hookMethod(setIsFlashPicMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isEnabledRuntime()) return;
                        param.args[0] = Boolean.FALSE;
                    }
                });
            }

            AppLogger.i(TAG, "已成功挂载 PicElement 闪照特征中和探针！");

        } catch (Throwable t) {
            AppLogger.e(TAG, "hookPicElement 异常", t);
        }
    }

    private void hookPicContentComponent(ClassLoader cl) {
        try {
            Class<?> picCompClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.aio.msglist.holder.component.pic.AIOPicContentComponent", cl);
            if (picCompClass == null) return;

            for (Method m : picCompClass.getDeclaredMethods()) {
                if (m.getParameterTypes().length >= 2 && m.getName().equals("A1")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isEnabledRuntime()) return;
                            if (param.args.length > 1 && param.args[1] != null) {
                                Object aioMsgItem = param.args[1];
                                Object msgRecord = null;
                                try {
                                    msgRecord = XposedHelpers.callMethod(aioMsgItem, "getMsgRecord");
                                } catch (Exception e) {
                                    try {
                                        msgRecord = XposedHelpers.getObjectField(aioMsgItem, "msgRecord");
                                    } catch (Exception e2) {
                                        AppLogger.d(TAG, "获取msgRecord安全跳过: " + e2.getMessage());
                                    }
                                }
                                if (msgRecord != null) {
                                    neutralizeMsgRecord(msgRecord);
                                }
                            }
                        }
                    });
                    AppLogger.i(TAG, "已成功挂载 AIOPicContentComponent 闪照渲染前置转换探针！");
                    break;
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookPicContentComponent 异常", t);
        }
    }

    public static void neutralizeMsgRecord(Object msgRecord) {
        if (msgRecord == null) return;
        if (!isEnabledRuntime()) return;
        try {
            long seq = 0;
            try {
                seq = XposedHelpers.getLongField(msgRecord, "msgSeq");
            } catch (Exception e) {
                AppLogger.d(TAG, "neutralizeMsgRecord 读取msgSeq安全跳过: " + e.getMessage());
            }

            boolean isRealFlash = false;

            // 1. 严格针对 8194 或 12288 清除 8192 掩码
            try {
                Field subMsgTypeField = msgRecord.getClass().getDeclaredField("subMsgType");
                subMsgTypeField.setAccessible(true);
                int subMsgType = subMsgTypeField.getInt(msgRecord);
                if (subMsgType == 8194 || subMsgType == 12288) {
                    isRealFlash = true;
                    subMsgTypeField.setInt(msgRecord, subMsgType & ~FLASH_PIC_MASK);
                    AppLogger.i(TAG, "【成功破除闪照】已记录并清除 subMsgType 8192 掩码 (seq=" + seq + ")");
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "neutralizeMsgRecord 修改subMsgType安全跳过: " + e.getMessage());
            }

            // 2. 检查并中和 PicElement.isFlashPic
            List<?> elements = (List<?>) XposedHelpers.getObjectField(msgRecord, "elements");
            if (elements != null && !elements.isEmpty()) {
                for (Object elem : elements) {
                    if (elem == null) continue;
                    try {
                        Object picElem = XposedHelpers.getObjectField(elem, "picElement");
                        if (picElem != null) {
                            Field flashField = picElem.getClass().getDeclaredField("isFlashPic");
                            flashField.setAccessible(true);
                            Object current = flashField.get(picElem);
                            if (Boolean.TRUE.equals(current)) {
                                isRealFlash = true;
                                flashField.set(picElem, Boolean.FALSE);
                                AppLogger.i(TAG, "【成功破除闪照】已将图片 isFlashPic 覆写为正常 (seq=" + seq + ")");
                            }
                        }
                    } catch (Exception e) {
                        AppLogger.d(TAG, "neutralizeMsgRecord 中和isFlashPic安全跳过: " + e.getMessage());
                    }
                }
            }

            if (isRealFlash && seq > 0) {
                flashPicMsgSeqs.add(seq);
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "neutralizeMsgRecord 总体流程安全跳过: " + e.getMessage());
        }
    }
}
