package com.emoji.reactor.feature.impl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import com.emoji.reactor.R;
import com.emoji.reactor.feature.BaseFeature;
import com.emoji.reactor.feature.helper.BubbleBadgeHelper;
import com.emoji.reactor.hook.RemoteConfigHelper;
import com.emoji.reactor.util.AppLogger;
import com.emoji.reactor.util.ProtoHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 消息防撤回核心功能特征 (AntiRecallFeature)
 * 融合 QAuxiliary 与 TimTool / TCQT 业界权威方案：
 * 1. 核心闸门前置拦截：在 MSF 网络总闸门 (IQQNTWrapperSession$CppProxy#onMsfPush) 捕获撤回数据包
 * 2. 虚假序号篡改法 (Rewrite MsgSeq to 1)：将推送包中的目标撤回序号篡改为 1，并放行给 C++ NT 内核。
 *    C++ 正常响应网络状态，但在本地 SQLite 中寻找 msgSeq=1 查无此人，绝对不执行删库，消息完好保留在本地！
 * 3. 监听器双重短路：同步短路 IKernelMsgListener#onMsgRecall 与 onMsgDelete
 * 4. 气泡视觉胶囊徽标：在聊天气泡内容顶端优雅挂载红底白字「已撤回」胶囊标签，静默拦截零弹窗打扰
 */
public class AntiRecallFeature extends BaseFeature {

    public static final String KEY = "feature_anti_recall";
    private static final String TAG = "AntiRecallFeature";
    private static final int MAX_TRACKED_SEQS = 4096;

    public static final Set<Long> recalledMsgSeqs = createLruSet(MAX_TRACKED_SEQS);
    private static final Set<Class<?>> hookedListenerClasses = Collections.synchronizedSet(new HashSet<>());
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        return R.string.feature_anti_recall_title;
    }

    @Override
    public int getDescRes() {
        return R.string.feature_anti_recall_desc;
    }

    @Override
    public boolean isDefaultEnabled() {
        return true;
    }

    public static boolean isEnabledRuntime() {
        return RemoteConfigHelper.isModuleEnabled() && RemoteConfigHelper.isFeatureEnabled(KEY, true);
    }

    public static boolean isRecalled(long msgSeq) {
        if (!isEnabledRuntime()) return false;
        return msgSeq != 0 && recalledMsgSeqs.contains(msgSeq);
    }

    @Override
    public void onInit(ClassLoader cl) throws Throwable {
        hookMsfPushGate(cl);
        hookKernelMsgListenerRegistration(cl);
        hookBubbleMsgItemVB(cl);
        hookAioMsgItem(cl);
    }

    /**
     * 防线 1：拦截 MSF 网络推送总闸门 (IQQNTWrapperSession$CppProxy#onMsfPush)
     * 同时接管 MsgPush (单条实时推送) 与 InfoSyncPush (同步批次推送)
     */
    private void hookMsfPushGate(ClassLoader cl) {
        try {
            Class<?> cppProxyClass = XposedHelpers.findClassIfExists(
                    "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy", cl);
            if (cppProxyClass == null) {
                cppProxyClass = XposedHelpers.findClassIfExists(
                        "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession", cl);
            }
            if (cppProxyClass == null) return;

            int hookedCount = 0;
            for (Method m : cppProxyClass.getDeclaredMethods()) {
                if ("onMsfPush".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isEnabledRuntime()) return;
                            if (param == null || param.args == null || param.args.length < 2) return;

                            String cmd = param.args[0] instanceof String ? (String) param.args[0] : "";
                            byte[] protoBuf = param.args[1] instanceof byte[] ? (byte[]) param.args[1] : null;

                            if (protoBuf == null || protoBuf.length < 5) return;

                            // 1. 处理在线单条推送 (MsgPush)
                            if ("trpc.msg.olpush.OlPushService.MsgPush".equals(cmd) || cmd.contains("MsgPush")) {
                                long[] outSeq = new long[1];
                                String[] outDesc = new String[1];
                                byte[] rewritten = rewriteMsgPush(protoBuf, outSeq, outDesc);
                                if (rewritten != null && outSeq[0] > 0) {
                                    param.args[1] = rewritten;
                                    recalledMsgSeqs.add(outSeq[0]);
                                    AppLogger.i(TAG, "【防撤回成功】已篡改 " + outDesc[0] + " 撤回包序号为1! 真实被撤回Seq=" + outSeq[0]);

                                    final long targetSeq = outSeq[0];
                                    mainHandler.post(() -> BubbleBadgeHelper.notifyMessageRecalledRealtime(targetSeq));
                                }
                            }
                            // 2. 处理同步批次推送 (InfoSyncPush)
                            else if ("trpc.msg.register_proxy.RegisterProxy.InfoSyncPush".equals(cmd) || cmd.contains("InfoSyncPush")) {
                                List<Long> outSeqs = new ArrayList<>();
                                List<String> outDescs = new ArrayList<>();
                                byte[] rewritten = rewriteInfoSyncPush(protoBuf, outSeqs, outDescs);
                                if (rewritten != null && !outSeqs.isEmpty()) {
                                    param.args[1] = rewritten;
                                    for (int i = 0; i < outSeqs.size(); i++) {
                                        long targetSeq = outSeqs.get(i);
                                        String desc = outDescs.get(i);
                                        recalledMsgSeqs.add(targetSeq);
                                        AppLogger.i(TAG, "【防撤回成功】已篡改同步批次 " + desc + " 撤回包序号为1! 真实被撤回Seq=" + targetSeq);
                                        mainHandler.post(() -> BubbleBadgeHelper.notifyMessageRecalledRealtime(targetSeq));
                                    }
                                }
                            }
                        }
                    });
                    hookedCount++;
                }
            }
            AppLogger.i(TAG, "已成功在 IQQNTWrapperSession 挂载 MSF Push 网络总闸门探针 (方法数: " + hookedCount + ")！");

        } catch (Throwable t) {
            AppLogger.e(TAG, "hookMsfPushGate 异常", t);
        }
    }

    /**
     * 防线 2：拦截 IKernelMsgListener 回调作为次级防护 (支持字符串与数字 Seq 转换)
     */
    private void hookKernelMsgListenerRegistration(ClassLoader cl) {
        String[] targetClasses = {
                "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService$CppProxy",
                "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService",
                "com.tencent.qqnt.msg.api.impl.MsgServiceImpl"
        };

        for (String className : targetClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
                if (clazz == null) continue;

                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().contains("addKernelMsgListener")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args == null || param.args.length == 0) return;
                                Object listenerObj = param.args[param.args.length - 1];
                                if (listenerObj != null) {
                                    hookListenerInstance(listenerObj);
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                AppLogger.d(TAG, "hookMsgService 监听器安全跳过: " + e.getMessage());
            }
        }
    }

    private void hookListenerInstance(Object listener) {
        if (listener == null) return;
        Class<?> listenerClass = listener.getClass();
        if (!hookedListenerClasses.add(listenerClass)) return;

        try {
            for (Method m : listenerClass.getDeclaredMethods()) {
                if ("onMsgRecall".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isEnabledRuntime()) return;
                            if (param.args == null || param.args.length < 3) return;
                            long msgSeq = 0L;
                            if (param.args[2] instanceof Number) {
                                msgSeq = ((Number) param.args[2]).longValue();
                            } else if (param.args[2] != null) {
                                try {
                                    msgSeq = Long.parseLong(param.args[2].toString().trim());
                                } catch (Exception e) {
                                    AppLogger.d(TAG, "parse msgSeq 失败: " + e.getMessage());
                                }
                            }
                            if (msgSeq > 0) {
                                recalledMsgSeqs.add(msgSeq);
                                param.setResult(null);
                                AppLogger.i(TAG, "【监听器防撤回切面触发】短路 onMsgRecall，msgSeq=" + msgSeq);

                                final long targetSeq = msgSeq;
                                mainHandler.post(() -> BubbleBadgeHelper.notifyMessageRecalledRealtime(targetSeq));
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            AppLogger.d(TAG, "hookListenerInstance 安全跳过: " + e.getMessage());
        }
    }

    /**
     * 防线 3A (核心 UI 切面)：Hook AIOBubbleMsgItemVB.P 与 handleUIState (MVI)
     */
    private void hookBubbleMsgItemVB(ClassLoader cl) {
        try {
            Class<?> bubbleVbClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB", cl);
            Class<?> aioMsgItemClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.aio.msg.AIOMsgItem", cl);

            if (bubbleVbClass != null) {
                if (aioMsgItemClass != null) {
                    Method pMethod = XposedHelpers.findMethodExactIfExists(bubbleVbClass, "P", aioMsgItemClass);
                    if (pMethod != null) {
                        XposedBridge.hookMethod(pMethod, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                                try {
                                    View rootView = (View) XposedHelpers.getObjectField(param.thisObject, "e");
                                    if (rootView instanceof ViewGroup) {
                                        boolean moduleOn = RemoteConfigHelper.isModuleEnabled();
                                        boolean anyBadgeOn = moduleOn && (AntiRecallFeature.isEnabledRuntime() || FlashPicFeature.isEnabledRuntime());
                                        if (!anyBadgeOn) {
                                            BubbleBadgeHelper.hideBadges((ViewGroup) rootView);
                                            return;
                                        }
                                        Object aioMsgItem = param.args[0];
                                        Object msgRecord = XposedHelpers.callMethod(aioMsgItem, "getMsgRecord");
                                        if (msgRecord != null) {
                                            BubbleBadgeHelper.onBindMessageView((ViewGroup) rootView, msgRecord);
                                        }
                                    }
                                } catch (Throwable t) {
                                    AppLogger.d(TAG, "BubbleMsgItemVB.P hook 执行异常: " + t.getMessage());
                                }
                            }
                        });
                        AppLogger.i(TAG, "已成功挂载 AIOBubbleMsgItemVB.P 气泡胶囊徽标渲染探针！");
                    }
                }

                // 挂载 handleUIState MVI 状态刷新回调 (TCQT 业界标准)
                for (Method m : bubbleVbClass.getDeclaredMethods()) {
                    if ("handleUIState".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                Object state = param.args[0];
                                if (state == null) return;
                                if (state.getClass().getName().contains("AIOMsgItemState")) {
                                    try {
                                        View rootView = (View) XposedHelpers.getObjectField(param.thisObject, "e");
                                        if (rootView instanceof ViewGroup) {
                                            boolean moduleOn = RemoteConfigHelper.isModuleEnabled();
                                            boolean anyBadgeOn = moduleOn && (AntiRecallFeature.isEnabledRuntime() || FlashPicFeature.isEnabledRuntime());
                                            if (!anyBadgeOn) {
                                                BubbleBadgeHelper.hideBadges((ViewGroup) rootView);
                                                return;
                                            }
                                            Object aioMsgItem = null;
                                            try {
                                                aioMsgItem = XposedHelpers.getObjectField(param.thisObject, "m");
                                            } catch (Exception e) {
                                                AppLogger.d(TAG, "获取字段m安全跳过: " + e.getMessage());
                                            }
                                            if (aioMsgItem == null) {
                                                for (Field f : state.getClass().getDeclaredFields()) {
                                                    f.setAccessible(true);
                                                    Object val = f.get(state);
                                                    if (val != null && val.getClass().getName().contains("AIOMsgItem")) {
                                                        aioMsgItem = val;
                                                        break;
                                                    }
                                                }
                                            }
                                            if (aioMsgItem != null) {
                                                Object msgRecord = XposedHelpers.callMethod(aioMsgItem, "getMsgRecord");
                                                if (msgRecord != null) {
                                                    BubbleBadgeHelper.onBindMessageView((ViewGroup) rootView, msgRecord);
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        AppLogger.d(TAG, "handleUIState 刷新徽标安全跳过: " + e.getMessage());
                                    }
                                }
                            }
                        });
                        AppLogger.i(TAG, "已成功挂载 AIOBubbleMsgItemVB#handleUIState 动态徽标刷新探针！");
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookBubbleMsgItemVB 异常", t);
        }
    }

    /**
     * 防线 3B：BaseContentComponent (A1/R0/V0) 真实内容组件高精度挂载
     */
    private void hookAioMsgItem(ClassLoader cl) {
        try {
            Class<?> baseHolderClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent", cl);

            if (baseHolderClass != null) {
                int hookedCount = 0;
                for (Method m : baseHolderClass.getDeclaredMethods()) {
                    String mName = m.getName();
                    if ("A1".equals(mName) || "R0".equals(mName) || "V0".equals(mName)) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                boolean moduleOn = RemoteConfigHelper.isModuleEnabled();
                                boolean anyBadgeOn = moduleOn && (AntiRecallFeature.isEnabledRuntime() || FlashPicFeature.isEnabledRuntime());
                                if (!anyBadgeOn) return;
                                if (param.args == null || param.args.length == 0) return;

                                Object aioMsgItem = null;
                                for (Object arg : param.args) {
                                    if (arg != null && (arg.getClass().getName().contains("AIOMsgItem") || arg.getClass().getName().contains("msglist.a"))) {
                                        aioMsgItem = arg;
                                        break;
                                    }
                                }
                                if (aioMsgItem == null) return;

                                Object msgRecord = null;
                                try {
                                    msgRecord = XposedHelpers.callMethod(aioMsgItem, "getMsgRecord");
                                } catch (Exception e) {
                                    AppLogger.d(TAG, "call getMsgRecord 安全跳过: " + e.getMessage());
                                }
                                if (msgRecord == null) return;

                                Object compObj = param.thisObject;
                                View compView = null;
                                try {
                                    compView = (View) XposedHelpers.callMethod(compObj, "B1");
                                } catch (Exception e) {
                                    AppLogger.d(TAG, "call B1 安全跳过: " + e.getMessage());
                                }
                                if (compView == null) {
                                    try {
                                        compView = (View) XposedHelpers.callMethod(compObj, "getHostView");
                                    } catch (Exception e) {
                                        AppLogger.d(TAG, "call getHostView 安全跳过: " + e.getMessage());
                                    }
                                }

                                if (compView != null) {
                                    Context compCtx = compView.getContext();
                                    int opvId = 0;
                                    try {
                                        opvId = compCtx.getResources().getIdentifier("opv", "id", "com.tencent.mobileqq");
                                    } catch (Exception e) {
                                        AppLogger.d(TAG, "get opv id 安全跳过: " + e.getMessage());
                                    }

                                    android.view.ViewParent cur = compView.getParent();
                                    View bubbleDirectChild = compView;
                                    ViewGroup targetRoot = null;
                                    View opvView = null;

                                    while (cur != null) {
                                        if (opvId != 0 && cur instanceof View && ((View) cur).getId() == opvId) {
                                            opvView = (View) cur;
                                        }
                                        if (cur.getParent() != null && cur.getParent().getClass().getName().contains("RecyclerView")) {
                                            targetRoot = (ViewGroup) cur;
                                            break;
                                        }
                                        if (cur instanceof View) {
                                            bubbleDirectChild = (View) cur;
                                        }
                                        cur = cur.getParent();
                                    }

                                    View finalBubble = (opvView != null) ? opvView : bubbleDirectChild;
                                    if (targetRoot != null && finalBubble != null) {
                                        BubbleBadgeHelper.onBindComponentView(targetRoot, finalBubble, compView, msgRecord);
                                    }
                                }
                            }
                        });
                        hookedCount++;
                    }
                }
                AppLogger.i(TAG, "已成功挂载 BaseContentComponent 消息内容组件探针 (方法数: " + hookedCount + ")！");
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookAioMsgItem 异常", t);
        }
    }

    public static class RewriteResult {
        public final byte[] newBytes;
        public final long realSeq;
        public final String desc;

        public RewriteResult(byte[] newBytes, long realSeq, String desc) {
            this.newBytes = newBytes;
            this.realSeq = realSeq;
            this.desc = desc;
        }
    }

    /**
     * 行业公认标杆算法：精准重构 QQMessage 二进制 Protobuf 流，将撤回目标序号篡改为 1
     */
    public static RewriteResult rewriteQQMessage(byte[] qqMsgBytes) {
        try {
            List<ProtoHelper.ProtoField> qqMsgFields = ProtoHelper.parseFields(qqMsgBytes);
            if (qqMsgFields == null || qqMsgFields.isEmpty()) return null;

            int msgType = 0, msgSubType = 0, subSeq = 0;
            byte[] opInfo = null;
            int bodyFieldIndex = -1;

            for (int i = 0; i < qqMsgFields.size(); i++) {
                ProtoHelper.ProtoField f = qqMsgFields.get(i);
                if (f.fieldNumber == 2) { // messageContentInfo
                    List<ProtoHelper.ProtoField> cfList = ProtoHelper.parseFields(f.payload);
                    if (cfList != null) {
                        for (ProtoHelper.ProtoField cf : cfList) {
                            ProtoHelper.ProtoVarInt vi = ProtoHelper.readVarInt(cf.payload, 0);
                            if (vi != null) {
                                if (cf.fieldNumber == 1) msgType = (int) vi.value;
                                if (cf.fieldNumber == 2) msgSubType = (int) vi.value;
                                if (cf.fieldNumber == 3) subSeq = (int) vi.value;
                            }
                        }
                    }
                } else if (f.fieldNumber == 3) { // messageBody
                    bodyFieldIndex = i;
                    List<ProtoHelper.ProtoField> bfList = ProtoHelper.parseFields(f.payload);
                    if (bfList != null) {
                        for (ProtoHelper.ProtoField bf : bfList) {
                            if (bf.fieldNumber == 2) { // field 2: operationInfo
                                opInfo = bf.payload;
                                break;
                            }
                        }
                    }
                }
            }

            if (opInfo == null || bodyFieldIndex == -1) return null;

            boolean isGroupRecall = (msgType == com.emoji.reactor.util.QQMsgConstants.MSG_TYPE_GROUP_RECALL)
                    && (subSeq == com.emoji.reactor.util.QQMsgConstants.SUB_TYPE_GROUP_RECALL || msgSubType == com.emoji.reactor.util.QQMsgConstants.SUB_TYPE_GROUP_RECALL);
            boolean isC2CRecall = (msgType == com.emoji.reactor.util.QQMsgConstants.MSG_TYPE_C2C_RECALL)
                    && (subSeq == com.emoji.reactor.util.QQMsgConstants.SUB_TYPE_C2C_RECALL || msgSubType == com.emoji.reactor.util.QQMsgConstants.SUB_TYPE_C2C_RECALL);

            if (!isGroupRecall && !isC2CRecall) return null;

            byte[] newOpInfo = null;
            long realSeq = 0;
            String desc = "";

            // 1. 群聊撤回: 自适应探测 QQ 路由头偏移（消除硬编码魔数 7 字节风险）
            int groupOffset = isGroupRecall ? findGroupRecallProtoOffset(opInfo) : -1;
            if (isGroupRecall && groupOffset >= 0 && groupOffset < opInfo.length) {
                byte[] rawSec = new byte[opInfo.length - groupOffset];
                System.arraycopy(opInfo, groupOffset, rawSec, 0, rawSec.length);
                List<ProtoHelper.ProtoField> grFields = ProtoHelper.parseFields(rawSec);
                if (grFields != null) {
                    List<ProtoHelper.ProtoField> newGrFields = new ArrayList<>();
                    for (ProtoHelper.ProtoField gf : grFields) {
                        if (gf.fieldNumber == 37) { // GroupRecallOperationInfo.msg_seq
                            ProtoHelper.ProtoVarInt vi = ProtoHelper.readVarInt(gf.payload, 0);
                            if (vi != null && realSeq == 0 && vi.value > 1) {
                                realSeq = vi.value;
                            }
                            newGrFields.add(new ProtoHelper.ProtoField(37, 0, ProtoHelper.encodeVarInt(1)));
                        } else if (gf.fieldNumber == 11) { // info
                            List<ProtoHelper.ProtoField> infoFields = ProtoHelper.parseFields(gf.payload);
                            if (infoFields != null) {
                                List<ProtoHelper.ProtoField> newInfoFields = new ArrayList<>();
                                for (ProtoHelper.ProtoField inf : infoFields) {
                                    if (inf.fieldNumber == 3) { // msg_info
                                        List<ProtoHelper.ProtoField> miFields = ProtoHelper.parseFields(inf.payload);
                                        if (miFields != null) {
                                            List<ProtoHelper.ProtoField> newMiFields = new ArrayList<>();
                                            for (ProtoHelper.ProtoField mif : miFields) {
                                                if (mif.fieldNumber == 1) { // 真实的被撤回消息序列号 msg_seq!
                                                    ProtoHelper.ProtoVarInt mvi = ProtoHelper.readVarInt(mif.payload, 0);
                                                    if (mvi != null) {
                                                        if (mvi.value > 1) {
                                                            realSeq = mvi.value;
                                                        }
                                                        newMiFields.add(new ProtoHelper.ProtoField(1, 0, ProtoHelper.encodeVarInt(1))); // 篡改为 1
                                                    } else {
                                                        newMiFields.add(mif);
                                                    }
                                                } else {
                                                    newMiFields.add(mif);
                                                }
                                            }
                                            newInfoFields.add(new ProtoHelper.ProtoField(3, 2, ProtoHelper.serializeFields(newMiFields)));
                                        } else {
                                            newInfoFields.add(inf);
                                        }
                                    } else {
                                        newInfoFields.add(inf);
                                    }
                                }
                                newGrFields.add(new ProtoHelper.ProtoField(11, 2, ProtoHelper.serializeFields(newInfoFields)));
                            } else {
                                newGrFields.add(gf);
                            }
                        } else {
                            newGrFields.add(gf);
                        }
                    }
                    byte[] newSec = ProtoHelper.serializeFields(newGrFields);
                    newOpInfo = new byte[groupOffset + newSec.length];
                    System.arraycopy(opInfo, 0, newOpInfo, 0, groupOffset);
                    System.arraycopy(newSec, 0, newOpInfo, groupOffset, newSec.length);
                    desc = "群聊";
                }
            }

            // 2. 私聊撤回: msgType == 528, subSeq == 138 或 msgSubType == 138
            if (isC2CRecall && opInfo.length > 0) {
                List<ProtoHelper.ProtoField> c2cFields = ProtoHelper.parseFields(opInfo);
                if (c2cFields != null) {
                    List<ProtoHelper.ProtoField> newC2cFields = new ArrayList<>();
                    for (ProtoHelper.ProtoField cf : c2cFields) {
                        if (cf.fieldNumber == 1) { // info
                            List<ProtoHelper.ProtoField> infFields = ProtoHelper.parseFields(cf.payload);
                            if (infFields != null) {
                                List<ProtoHelper.ProtoField> newInfFields = new ArrayList<>();
                                for (ProtoHelper.ProtoField inf : infFields) {
                                    if (inf.fieldNumber == 20) { // msg_seq
                                        ProtoHelper.ProtoVarInt vi = ProtoHelper.readVarInt(inf.payload, 0);
                                        if (vi != null) {
                                            realSeq = vi.value;
                                            newInfFields.add(new ProtoHelper.ProtoField(20, 0, ProtoHelper.encodeVarInt(1))); // 篡改为 1
                                        } else {
                                            newInfFields.add(inf);
                                        }
                                    } else {
                                        newInfFields.add(inf);
                                    }
                                }
                                newC2cFields.add(new ProtoHelper.ProtoField(1, 2, ProtoHelper.serializeFields(newInfFields)));
                            } else {
                                newC2cFields.add(cf);
                            }
                        } else {
                            newC2cFields.add(cf);
                        }
                    }
                    newOpInfo = ProtoHelper.serializeFields(newC2cFields);
                    desc = "私聊";
                }
            }

            if (newOpInfo != null && realSeq > 0) {
                ProtoHelper.ProtoField oldBodyField = qqMsgFields.get(bodyFieldIndex);
                List<ProtoHelper.ProtoField> oldBodyFields = ProtoHelper.parseFields(oldBodyField.payload);
                List<ProtoHelper.ProtoField> newBodyFields = new ArrayList<>();
                if (oldBodyFields != null) {
                    for (ProtoHelper.ProtoField bf : oldBodyFields) {
                        if (bf.fieldNumber == 2) {
                            newBodyFields.add(new ProtoHelper.ProtoField(2, 2, newOpInfo));
                        } else {
                            newBodyFields.add(bf);
                        }
                    }
                }
                qqMsgFields.set(bodyFieldIndex, new ProtoHelper.ProtoField(3, 2, ProtoHelper.serializeFields(newBodyFields)));
                byte[] newQQMsg = ProtoHelper.serializeFields(qqMsgFields);
                return new RewriteResult(newQQMsg, realSeq, desc);
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "rewriteQQMessage 异常", t);
        }
        return null;
    }

    private static int findGroupRecallProtoOffset(byte[] opInfo) {
        if (opInfo == null || opInfo.length < 4) return -1;
        int[] candidates = {7, 0, 8, 6, 9, 10, 12};
        for (int cand : candidates) {
            if (cand < opInfo.length) {
                byte[] sub = new byte[opInfo.length - cand];
                System.arraycopy(opInfo, cand, sub, 0, sub.length);
                List<ProtoHelper.ProtoField> fields = ProtoHelper.parseFields(sub);
                if (fields != null) {
                    for (ProtoHelper.ProtoField f : fields) {
                        if (f.fieldNumber == 11 || f.fieldNumber == 37) {
                            return cand;
                        }
                    }
                }
            }
        }
        return -1;
    }

    private static byte[] rewriteMsgPush(byte[] buffer, long[] outRealSeq, String[] outDesc) {
        try {
            List<ProtoHelper.ProtoField> pushFields = ProtoHelper.parseFields(buffer);
            if (pushFields == null || pushFields.isEmpty()) return null;

            for (int i = 0; i < pushFields.size(); i++) {
                if (pushFields.get(i).fieldNumber == 1) { // qqMessage
                    RewriteResult res = rewriteQQMessage(pushFields.get(i).payload);
                    if (res != null) {
                        pushFields.set(i, new ProtoHelper.ProtoField(1, 2, res.newBytes));
                        outRealSeq[0] = res.realSeq;
                        outDesc[0] = res.desc;
                        return ProtoHelper.serializeFields(pushFields);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "rewriteMsgPush 异常", t);
        }
        return null;
    }

    private static byte[] rewriteInfoSyncPush(byte[] buffer, List<Long> outRealSeqs, List<String> outDescs) {
        try {
            List<ProtoHelper.ProtoField> pushFields = ProtoHelper.parseFields(buffer);
            if (pushFields == null || pushFields.isEmpty()) return null;

            boolean isRecallPush = false;
            int syncRecallIndex = -1;
            ProtoHelper.ProtoField syncRecallField = null;

            for (int i = 0; i < pushFields.size(); i++) {
                ProtoHelper.ProtoField f = pushFields.get(i);
                if (f.fieldNumber == 3) { // push_flag
                    ProtoHelper.ProtoVarInt flag = ProtoHelper.readVarInt(f.payload, 0);
                    if (flag != null && flag.value == 2) {
                        isRecallPush = true;
                    }
                } else if (f.fieldNumber == 8) { // sync_msg_recall
                    syncRecallIndex = i;
                    syncRecallField = f;
                }
            }

            if (!isRecallPush || syncRecallField == null || syncRecallIndex == -1) return null;

            List<ProtoHelper.ProtoField> sRecallFields = ProtoHelper.parseFields(syncRecallField.payload);
            if (sRecallFields == null) return null;

            boolean modifiedAny = false;
            List<ProtoHelper.ProtoField> newSRecallFields = new ArrayList<>();

            for (ProtoHelper.ProtoField rf : sRecallFields) {
                if (rf.fieldNumber == 4) { // sync_info_body (repeated)
                    List<ProtoHelper.ProtoField> bodyFields = ProtoHelper.parseFields(rf.payload);
                    if (bodyFields != null) {
                        List<ProtoHelper.ProtoField> newBodyFields = new ArrayList<>();
                        for (ProtoHelper.ProtoField bf : bodyFields) {
                            if (bf.fieldNumber == 8) { // msg (repeated QQMessage)
                                RewriteResult res = rewriteQQMessage(bf.payload);
                                if (res != null) {
                                    newBodyFields.add(new ProtoHelper.ProtoField(8, 2, res.newBytes));
                                    outRealSeqs.add(res.realSeq);
                                    outDescs.add(res.desc);
                                    modifiedAny = true;
                                } else {
                                    newBodyFields.add(bf);
                                }
                            } else {
                                newBodyFields.add(bf);
                            }
                        }
                        newSRecallFields.add(new ProtoHelper.ProtoField(4, 2, ProtoHelper.serializeFields(newBodyFields)));
                    } else {
                        newSRecallFields.add(rf);
                    }
                } else {
                    newSRecallFields.add(rf);
                }
            }

            if (modifiedAny) {
                pushFields.set(syncRecallIndex, new ProtoHelper.ProtoField(8, 2, ProtoHelper.serializeFields(newSRecallFields)));
                return ProtoHelper.serializeFields(pushFields);
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "rewriteInfoSyncPush 异常", t);
        }
        return null;
    }
}
