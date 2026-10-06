package com.emoji.reactor.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;

import com.emoji.reactor.util.AppLogger;
import com.emoji.reactor.util.BitmapCacheManager;
import com.emoji.reactor.util.StoragePaths;

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
    public static final String ITEM_TEXT = "贴表情";
    public static final int ITEM_ID = 0x7E110001;

    private static volatile Class<?> dynamicMenuItemClass;
    private static volatile Field actionField;

    public static boolean isOurMenuItem(Object itemObj) {
        if (itemObj == null) return false;
        String cls = itemObj.getClass().getName();
        if (cls.contains("EmojiReactor") || cls.contains("Generated")) return true;
        try {
            Method m = itemObj.getClass().getMethod("f");
            Object title = m.invoke(itemObj);
            if (title != null && title.toString().contains("贴表情")) return true;
        } catch (Exception e) {
            AppLogger.d("QQCustomMenuItemHelper", "isOurMenuItem 反射安全跳过: " + e.getMessage());
        }
        return itemObj.toString().contains("贴表情");
    }

    /**
     * 将用户自定义或 App 默认粉萌图标注入到菜单项的 ImageView 中
     */
    public static void applyCustomIconToView(ImageView iv) {
        if (iv == null) return;
        try {
            Context ctx = iv.getContext();
            Bitmap customBm = null;

            // 1. 最高优先级：从 ContentProvider 跨进程传递的 Base64 字符串解析用户自定义图标
            String customBase64 = RemoteConfigHelper.getCustomMenuIconBase64(ctx);
            if (customBase64 != null && !customBase64.trim().isEmpty()) {
                byte[] bytes = android.util.Base64.decode(customBase64, android.util.Base64.DEFAULT);
                if (bytes != null && bytes.length > 0) {
                    customBm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            }

            // 2. 次高优先级：尝试从文件读取用户自定义图片 (兜底)
            if (customBm == null) {
                File customFile = StoragePaths.getCustomMenuIconFile();
                if (!customFile.exists() || customFile.length() == 0) {
                    customFile = StoragePaths.getSafeMediaCustomMenuIconFile();
                }
                if (customFile.exists() && customFile.length() > 0) {
                    customBm = BitmapCacheManager.loadBitmap(customFile.getAbsolutePath(), 128, 128);
                }
            }

            // 3. 用户如果设置了自定义图片，立即设置
            if (customBm != null && !customBm.isRecycled()) {
                iv.setImageBitmap(customBm);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                AppLogger.i(TAG, "已成功应用用户相册自定义菜单图标！");
                return;
            }

            // 4. 默认形态：直接通过 Android 官方 PackageManager 读取本模块的超清 App 图标 (杜绝与“复制”图标撞车)
            try {
                android.graphics.drawable.Drawable appIcon = ctx.getPackageManager().getApplicationIcon("com.emoji.reactor");
                if (appIcon != null) {
                    iv.setImageDrawable(appIcon);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    AppLogger.i(TAG, "已成功应用官方 App 默认粉萌菜单图标！");
                    return;
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "获取 App 默认图标失败", t);
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "设置自定义菜单图标异常", t);
        }
    }

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

            // 1. 拦截标题、Tag 与图标 Resource ID
            for (Method m : baseMenuItemCls.getDeclaredMethods()) {
                if (m.getReturnType() == String.class && m.getParameterTypes().length == 0) {
                    if ("e".equals(m.getName())) {
                        builder = builder.method(ElementMatchers.is(m)).intercept(FixedValue.value("EmojiReactorMenuItem"));
                    } else {
                        builder = builder.method(ElementMatchers.is(m)).intercept(FixedValue.value(ITEM_TEXT));
                    }
                } else if (m.getReturnType() == int.class && m.getParameterTypes().length == 0) {
                    if ("b".equals(m.getName())) {
                        // b() 返回图标 Drawable Resource ID，必须是 QQ 宿主内真实合法的图标 ID (0x7f081edd)，杜绝 NotFoundException
                        builder = builder.method(ElementMatchers.is(m)).intercept(FixedValue.value(0x7f081edd));
                    } else {
                        builder = builder.method(ElementMatchers.is(m)).intercept(FixedValue.value(ITEM_ID));
                    }
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
