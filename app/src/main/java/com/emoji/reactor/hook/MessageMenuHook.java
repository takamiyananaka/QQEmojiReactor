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

    private static volatile Class<?> baseMenuItemClass;
    private static volatile Method getMsgMethod;
    private static volatile String getListMethodName;
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
                            hookMenuLayoutItemRenderer(m);
                            hookExpandableLayoutPopulate(clazz);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookMenuLayoutItemRenderer(Method renderMethod) {
        if (renderMethod == null) return;
        try {
            XposedBridge.hookMethod(renderMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param == null || param.args == null || param.args.length < 2) return;
                    Object itemObj = param.args[1];
                    if (QQCustomMenuItemHelper.isOurMenuItem(itemObj)) {
                        Object resultView = param.getResult();
                        if (resultView instanceof View) {
                            AppLogger.i(TAG, "菜单单项构建 l() 拦截命中: " + itemObj);
                            fixImageViewInView((View) resultView);
                        }
                    }
                }
            });
            AppLogger.i(TAG, "已成功挂载菜单单项渲染探针！");
        } catch (Throwable t) {
            AppLogger.e(TAG, "挂载菜单单项渲染探针异常", t);
        }
    }

    private static void hookExpandableLayoutPopulate(Class<?> layoutClass) {
        if (layoutClass == null) return;
        try {
            // 防线 2：Hook QQCustomMenuExpandableLayout.s() - 全局排版结束后的全景校正
            for (Method m : layoutClass.getDeclaredMethods()) {
                if (m.getReturnType() == void.class && m.getParameterTypes().length == 0 && "s".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.thisObject instanceof android.view.ViewGroup) {
                                AppLogger.i(TAG, "菜单全景布局 s() 渲染结束，执行全景图标防御校正...");
                                fixMenuIconsInViewGroup((android.view.ViewGroup) param.thisObject);
                            }
                        }
                    });
                    AppLogger.i(TAG, "已成功挂载菜单全景布局 s() 探针！");
                }
            }

            // 防线 3：Hook QQCustomMenuExpandableLayout.addView(View, ...) - 动态添加容器时的即时拦截
            XposedBridge.hookAllMethods(layoutClass, "addView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof View) {
                        View v = (View) param.args[0];
                        if (QQCustomMenuItemHelper.isOurMenuItem(v.getTag())) {
                            AppLogger.i(TAG, "菜单容器 addView() 拦截命中，即时更新图标: " + v);
                            fixImageViewInView(v);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            AppLogger.e(TAG, "hookExpandableLayoutPopulate 异常", t);
        }
    }

    private static void fixMenuIconsInViewGroup(android.view.ViewGroup vg) {
        if (vg == null) return;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child != null) {
                if (QQCustomMenuItemHelper.isOurMenuItem(child.getTag())) {
                    fixImageViewInView(child);
                } else if (child instanceof android.view.ViewGroup) {
                    fixMenuIconsInViewGroup((android.view.ViewGroup) child);
                }
            }
        }
    }

    private static void fixImageViewInView(View v) {
        if (v == null) return;
        if (v instanceof android.widget.ImageView) {
            QQCustomMenuItemHelper.applyCustomIconToView((android.widget.ImageView) v);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof android.widget.ImageView) {
                    QQCustomMenuItemHelper.applyCustomIconToView((android.widget.ImageView) child);
                    break;
                }
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
                            Method targetMenuMethod = findMethodRecursive(compClass, getListMethodName);
                            if (targetMenuMethod != null) {
                                XposedBridge.hookMethod(targetMenuMethod, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                        handleComponentMenuGenerated(p, compClass);
                                    }
                                });
                            }
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
                if (item != null && item.toString().contains("贴表情")) return;
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
                    AppLogger.i(TAG, "【成功注入】在群聊组件 " + compClass.getSimpleName() + " 注入当前消息的【贴表情】");
                }
            }

        } catch (Throwable t) {
            AppLogger.e(TAG, "handleComponentMenuGenerated 异常", t);
        }
    }

    public static void showGroupSelectDialog(Context context, Object msgRecord) {
        AppLogger.i(TAG, "用户触发长按菜单【贴表情】，准备弹出方案选择...");
        if (context == null || msgRecord == null) {
            AppLogger.e(TAG, "无法弹出方案选择：context 或 msgRecord 为空", null);
            return;
        }

        // 解包 Context 并校验 Activity 存活，杜绝 BadTokenException
        android.app.Activity activity = resolveActivity(context);
        if (activity == null || activity.isFinishing()) {
            AppLogger.e(TAG, "当前 Context 并非有效运行中的 Activity，取消弹窗以防 BadTokenException", null);
            return;
        }

        List<EmojiGroup> groups = RemoteConfigHelper.getGroups(activity);
        if (groups == null || groups.isEmpty()) return;

        if (groups.size() == 1) {
            ReactionExecutor.executeBatchReaction(activity, msgRecord, groups.get(0));
            return;
        }

        String[] items = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            EmojiGroup g = groups.get(i);
            int count = Math.min(g.getItems().size(), 20);
            items[i] = g.getName() + " (" + count + "个表情 / " + g.getDelayMs() + "ms)";
        }

        new AlertDialog.Builder(activity)
                .setTitle("🚀 选择贴表情方案")
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < groups.size()) {
                        ReactionExecutor.executeBatchReaction(activity, msgRecord, groups.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static android.app.Activity resolveActivity(Context context) {
        Context cur = context;
        while (cur instanceof android.content.ContextWrapper) {
            if (cur instanceof android.app.Activity) {
                return (android.app.Activity) cur;
            }
            cur = ((android.content.ContextWrapper) cur).getBaseContext();
        }
        return null;
    }

    private static Method findMethodRecursive(Class<?> clazz, String methodName) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                Method m = cur.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
            cur = cur.getSuperclass();
        }
        return null;
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
