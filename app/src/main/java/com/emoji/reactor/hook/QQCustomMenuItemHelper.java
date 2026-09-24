package com.emoji.reactor.hook;

import android.content.Context;

import com.emoji.reactor.util.AppLogger;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.android.AndroidClassLoadingStrategy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * QQ NT 消息菜单项动态构建器（系统级 Runnable 隔离版）
 * 彻底消除跨 ClassLoader 导致的 ClassNotFoundException 闪退
 */
public class QQCustomMenuItemHelper {

    private static final String TAG = "QQEmojiReactor_Item";
    public static final String ITEM_TEXT = "🚀 一键贴表情";
    public static final int ITEM_ID = 0x7E110001;

    private static volatile Class<?> dynamicMenuItemClass;
    private static Field actionField;

    /**
     * 创建一个合法的 QQ NT 菜单项实例
     *
     * @param context         当前上下文
     * @param aioMsgItem      当前长按气泡的 AIOMsgItem
     * @param baseMenuItemCls QQ 内部的 AbstractQQCustomMenuItem 类
     * @param onClickAction   属于该特定消息的点击回调
     */
    public static Object createMenuItem(Context context, Object aioMsgItem, Class<?> baseMenuItemCls, Runnable onClickAction) {
        if (aioMsgItem == null || baseMenuItemCls == null) {
            return null;
        }

        try {
            ensureClassGenerated(context, baseMenuItemCls);
            if (dynamicMenuItemClass == null) {
                return null;
            }

            for (Constructor<?> c : dynamicMenuItemClass.getDeclaredConstructors()) {
                Class<?>[] pts = c.getParameterTypes();
                Object[] args = new Object[pts.length];
                for (int i = 0; i < pts.length; i++) {
                    if (pts[i].isInstance(aioMsgItem) || pts[i].getName().contains("AIOMsgItem") || pts[i] == Object.class) {
                        args[i] = aioMsgItem;
                    } else if (Context.class.isAssignableFrom(pts[i])) {
                        args[i] = context;
                    } else if (pts[i] == int.class) {
                        args[i] = ITEM_ID;
                    } else if (pts[i] == boolean.class) {
                        args[i] = false;
                    } else if (pts[i] == String.class) {
                        args[i] = ITEM_TEXT;
                    } else {
                        args[i] = null;
                    }
                }
                try {
                    c.setAccessible(true);
                    Object itemInstance = c.newInstance(args);
                    if (itemInstance != null && onClickAction != null) {
                        if (actionField != null) {
                            // 将当前长按的消息动作直接绑定在系统级的 action 字段中
                            actionField.set(itemInstance, onClickAction);
                        }
                        return itemInstance;
                    }
                } catch (Throwable t) {
                    AppLogger.e(TAG, "菜单项构造参数尝试失败: " + pts.length, t);
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "创建动态菜单项失败", t);
        }

        return null;
    }

    private static synchronized void ensureClassGenerated(Context context, Class<?> baseMenuItemCls) {
        if (dynamicMenuItemClass != null) return;
        try {
            File dir = context.getDir("generated_emoji", Context.MODE_PRIVATE);
            Method runMethod = Runnable.class.getMethod("run");

            DynamicType.Builder<?> builder = new ByteBuddy()
                    .subclass(baseMenuItemCls)
                    .name("com.tencent.qqnt.aio.menu.ui.GeneratedEmojiReactorMenuItem")
                    // 定义系统级字段 public Runnable action，所有 ClassLoader 100% 互相可见
                    .defineField("action", Runnable.class, Modifier.PUBLIC);

            // 1. 拦截标题与 ID
            for (Method m : baseMenuItemCls.getDeclaredMethods()) {
                if (m.getReturnType() == String.class && m.getParameterTypes().length == 0) {
                    builder = builder.method(ElementMatchers.is(m)).intercept(FixedValue.value(ITEM_TEXT));
                } else if (m.getReturnType() == int.class && m.getParameterTypes().length == 0) {
                    builder = builder.method(ElementMatchers.is(m)).intercept(FixedValue.value(ITEM_ID));
                }
            }

            // 2. 拦截具体点击处理方法：在实例自身上调用 this.action.run()，完全不引用任何外部第三方模块类
            for (Method m : baseMenuItemCls.getDeclaredMethods()) {
                if (m.getReturnType() == void.class && m.getParameterTypes().length == 0) {
                    String name = m.getName().toLowerCase();
                    if (Modifier.isAbstract(m.getModifiers()) || name.contains("click") || name.contains("perform") || name.equals("h")) {
                        builder = builder.method(ElementMatchers.is(m))
                                .intercept(MethodCall.invoke(runMethod).onField("action"));
                    }
                }
            }

            dynamicMenuItemClass = builder.make()
                    .load(baseMenuItemCls.getClassLoader(), new AndroidClassLoadingStrategy.Wrapping(dir))
                    .getLoaded();

            actionField = dynamicMenuItemClass.getField("action");
            actionField.setAccessible(true);

            AppLogger.i(TAG, "动态菜单项类已成功安全加载（纯系统级接口对接）: " + dynamicMenuItemClass.getName());

        } catch (Throwable t) {
            AppLogger.e(TAG, "动态生成菜单类异常", t);
        }
    }
}
