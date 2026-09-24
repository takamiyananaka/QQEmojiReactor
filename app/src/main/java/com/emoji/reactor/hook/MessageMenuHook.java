package com.emoji.reactor.hook;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;

import com.emoji.reactor.engine.ReactionExecutor;
import com.emoji.reactor.model.EmojiGroup;
import com.emoji.reactor.util.AppLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * QQ NT 消息长按菜单注入器（工业级上下文过滤版）
 * 严格限制仅在群聊消息（chatType == 2）中展示，排除私聊、系统提示与撤回消息，杜绝误触与安全风险
 */
public class MessageMenuHook {

    private static final String TAG = "QQEmojiReactor_Menu";
    public static final int CHAT_TYPE_GROUP = 2; // QQ NT 官方群聊类型常量

    private static Class<?> baseMenuItemClass;
    private static Method getMsgMethod;
    private static String getListMethodName;
    private static final Set<Class<?>> hookedComponentClasses = Collections.synchronizedSet(new HashSet<>());

    public static void init(ClassLoader cl) {
        if (cl == null) return;

        findBaseMenuItemClass(cl);
        hookBaseContentComponent(cl);
    }

    private static void findBaseMenuItemClass(ClassLoader cl) {
        String[] menuClasses = {
                "com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout",
                "com.tencent.qqnt.aio.menu.ui.QQCustomMenuNoIconLayout"
        };
        for (String className : menuClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
                if (clazz == null) continue;
                for (Method m : clazz.getDeclaredMethods()) {
                    if (View.class.isAssignableFrom(m.getReturnType()) && m.getParameterTypes().length == 4) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts[0] == int.class && pts[2] == boolean.class && pts[3] == float[].class) {
                            baseMenuItemClass = pts[1];
                            AppLogger.i(TAG, "已精准锁定 baseMenuItemClass: " + baseMenuItemClass.getName());
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookBaseContentComponent(ClassLoader cl) {
        try {
            Class<?> baseCompClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent", cl);
            Class<?> msgItemClass = XposedHelpers.findClassIfExists(
                    "com.tencent.mobileqq.aio.msg.AIOMsgItem", cl);

            if (baseCompClass == null || msgItemClass == null) {
                AppLogger.e(TAG, "未找到 BaseContentComponent 或 AIOMsgItem", null);
                return;
            }

            for (Method m : baseCompClass.getDeclaredMethods()) {
                if (m.getReturnType().equals(msgItemClass) && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    getMsgMethod = m;
                    break;
                }
            }

            for (Method m : baseCompClass.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) && List.class.isAssignableFrom(m.getReturnType()) && m.getParameterTypes().length == 0) {
                    getListMethodName = m.getName();
                    AppLogger.i(TAG, "已锁定气泡菜单生成核心方法名: " + getListMethodName);
                    break;
                }
            }

            XposedBridge.hookAllConstructors(baseCompClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Class<?> compClass = param.thisObject.getClass();
                    if (!hookedComponentClasses.add(compClass)) return;

                    if (getListMethodName != null) {
                        try {
                            Method targetMenuMethod = compClass.getMethod(getListMethodName);
                            XposedBridge.hookMethod(targetMenuMethod, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                    handleComponentMenuGenerated(p, compClass);
                                }
                            });
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });

            AppLogger.i(TAG, "成功部署 BaseContentComponent 全量气泡长按监听！");

        } catch (Throwable t) {
            AppLogger.e(TAG, "hookBaseContentComponent 异常", t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleComponentMenuGenerated(XC_MethodHook.MethodHookParam param, Class<?> compClass) {
        try {
            // 1. 安全过滤：排除非普通聊天气泡（如系统提示、灰条、撤回提示等）
            String compName = compClass.getName();
            if (compName.contains("GrayTips") || compName.contains("Revoke") || compName.contains("Fold")) {
                return;
            }

            Object resultList = param.getResult();
            if (!(resultList instanceof List)) return;
            List<Object> list = (List<Object>) resultList;

            if (getMsgMethod == null) return;
            Object aioMsgItem = getMsgMethod.invoke(param.thisObject);
            if (aioMsgItem == null) return;

            final Object targetMsg = extractMsgRecord(aioMsgItem);
            if (targetMsg == null) return;

            // 实时嗅探长按消息中的所有表情元素（即看即得、零遗漏捕获新表情）
            QQDynamicEmojiDumper.sniffMsgRecordElements(targetMsg);

            // 2. 严格安全过滤：仅在群聊（chatType == 2）中展示贴表情，私聊及其他场景绝不注入
            int chatType = extractChatType(targetMsg);
            if (chatType != CHAT_TYPE_GROUP) {
                return;
            }

            final Context targetContext = extractContextFromComponent(param.thisObject);
            if (targetContext == null) return;

            if (!RemoteConfigHelper.isEnabled(targetContext)) return;

            // 防重检查
            for (Object item : list) {
                if (item != null && item.toString().contains("一键贴表情")) return;
            }

            if (baseMenuItemClass != null) {
                Object myItem = QQCustomMenuItemHelper.createMenuItem(
                        targetContext,
                        aioMsgItem,
                        baseMenuItemClass,
                        () -> showGroupSelectDialog(targetContext, targetMsg)
                );

                if (myItem != null) {
                    try {
                        list.add(0, myItem);
                    } catch (Throwable uoe) {
                        List<Object> mutableList = new ArrayList<>(list);
                        mutableList.add(0, myItem);
                        param.setResult(mutableList);
                    }
                    AppLogger.i(TAG, "【成功注入】在群聊组件 " + compClass.getSimpleName() + " 注入当前消息的【🚀 一键贴表情】");
                }
            }

        } catch (Throwable t) {
            AppLogger.e(TAG, "handleComponentMenuGenerated 异常", t);
        }
    }

    public static void showGroupSelectDialog(Context context, Object msgRecord) {
        AppLogger.i(TAG, "用户触发长按菜单【🚀 一键贴表情】，准备弹出方案选择...");
        if (context == null || msgRecord == null) {
            AppLogger.e(TAG, "无法弹出方案选择：context 或 msgRecord 为空", null);
            return;
        }

        List<EmojiGroup> groups = RemoteConfigHelper.getGroups(context);
        if (groups == null || groups.isEmpty()) return;

        if (groups.size() == 1) {
            ReactionExecutor.executeBatchReaction(context, msgRecord, groups.get(0));
            return;
        }

        String[] items = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            EmojiGroup g = groups.get(i);
            int count = Math.min(g.getItems().size(), 20);
            items[i] = g.getName() + " (" + count + "个表情 / " + g.getDelayMs() + "ms)";
        }

        new AlertDialog.Builder(context)
                .setTitle("🚀 选择一键贴表情方案")
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < groups.size()) {
                        ReactionExecutor.executeBatchReaction(context, msgRecord, groups.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static int extractChatType(Object msgRecord) {
        try {
            Object ct = XposedHelpers.getObjectField(msgRecord, "chatType");
            if (ct instanceof Number) return ((Number) ct).intValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Object extractMsgRecord(Object aioMsgItem) {
        if (aioMsgItem == null) return null;
        try {
            return XposedHelpers.callMethod(aioMsgItem, "getMsgRecord");
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.getObjectField(aioMsgItem, "msgRecord");
        } catch (Throwable ignored) {
        }
        if (aioMsgItem.getClass().getName().contains("MsgRecord")) {
            return aioMsgItem;
        }
        return null;
    }

    private static Context extractContextFromComponent(Object comp) {
        try {
            Class<?> c = comp.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (Context.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(comp);
                        if (val instanceof Context) return (Context) val;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
