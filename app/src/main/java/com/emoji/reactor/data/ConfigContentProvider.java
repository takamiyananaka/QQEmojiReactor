package com.emoji.reactor.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 官方标准跨进程配置 Provider
 * 为注入在 QQ 进程中的 Hook 模块提供免沙盒阻碍的配置读取通道
 */
public class ConfigContentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.emoji.reactor.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_GET_CONFIG = "get_config";
    public static final String METHOD_SAVE_DYNAMIC_FACES = "save_dynamic_faces";
    public static final String DYNAMIC_PREF_NAME = "dynamic_faces_cache";
    public static final String KEY_DYNAMIC_FACES = "faces_json";
    public static final String KEY_GROUPS_JSON = "groups_json";
    public static final String KEY_IS_ENABLED = "is_enabled";
    public static final String KEY_CUSTOM_ICON_BASE64 = "custom_icon_base64";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Context ctx = getContext();
        String caller = getCallingPackage();
        // 安全防御：严格校验调用方包名，仅允许 QQ 宿主与模块自身进程访问，杜绝第三方恶意应用探测
        if (caller != null) {
            String selfPkg = (ctx != null) ? ctx.getPackageName() : "com.emoji.reactor";
            if (!caller.equals(selfPkg) && !caller.equals("com.tencent.mobileqq") && !caller.equals("com.tencent.tim")) {
                com.emoji.reactor.util.AppLogger.e("ConfigContentProvider", "拦截未经授权的第三方应用跨进程访问: " + caller, null);
                throw new SecurityException("Unauthorized access from package: " + caller);
            }
        }

        if (METHOD_GET_CONFIG.equals(method)) {
            Bundle bundle = new Bundle();
            if (ctx != null) {
                SharedPreferences sp = ctx.getSharedPreferences(ConfigManager.PREF_NAME, Context.MODE_PRIVATE);
                bundle.putString(KEY_GROUPS_JSON, sp.getString(ConfigManager.KEY_GROUPS, ""));
                bundle.putBoolean(KEY_IS_ENABLED, sp.getBoolean(ConfigManager.KEY_ENABLED, true));
                bundle.putString(KEY_CUSTOM_ICON_BASE64, sp.getString(ConfigManager.KEY_CUSTOM_ICON, ""));
            }
            return bundle;
        } else if (METHOD_SAVE_DYNAMIC_FACES.equals(method) && arg != null) {
            if (ctx != null) {
                SharedPreferences sp = ctx.getSharedPreferences(DYNAMIC_PREF_NAME, Context.MODE_PRIVATE);
                sp.edit().putString(KEY_DYNAMIC_FACES, arg).apply();
            }
            Bundle bundle = new Bundle();
            bundle.putBoolean("success", true);
            return bundle;
        }
        return super.call(method, arg, extras);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
