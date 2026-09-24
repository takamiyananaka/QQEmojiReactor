package com.emoji.reactor.model;

import android.content.Context;

import com.emoji.reactor.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 官方正版超清表情字典
 * 1. 294 款 QQ 官方全量超清小黄脸 (已彻底剔除所有早年废弃的 <=32px 低清小图，全量采用 128x128 / 240x240 超清正版位图)
 * 2. 1354 款 手机 QQ 原生全部支持的 Emoji 字符全集
 */
public class DefaultPresets {

    private static final String TAG = "QQEmojiReactor_Presets";

    // QQ 官方 294 款正版超清小黄脸 ID (0 ~ 493，含给你一拳474、不是吧476、拜谢297等全部现代表情)
    public static final int[] ALL_OFFICIAL_FACE_IDS = new int[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 41,
            42, 43, 46, 49, 53, 56, 59, 60, 63, 64, 66, 67, 74, 75, 76, 77, 78, 79, 85, 86,
            89, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 114, 116,
            118, 119, 120, 121, 123, 124, 125, 129, 137, 144, 146, 147, 148, 169, 171, 172, 173, 174, 175, 176,
            177, 178, 179, 181, 182, 183, 185, 187, 201, 212, 245, 246, 247, 262, 263, 264, 265, 266, 267, 268,
            269, 270, 271, 272, 273, 277, 281, 282, 283, 284, 285, 286, 287, 289, 293, 294, 295, 297, 298, 299,
            300, 302, 303, 305, 306, 307, 311, 312, 314, 317, 318, 319, 320, 323, 324, 325, 326, 332, 333, 334,
            336, 337, 338, 339, 341, 342, 343, 344, 345, 346, 347, 349, 350, 351, 352, 353, 354, 355, 356, 357,
            358, 359, 360, 361, 362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372, 373, 374, 375, 376, 377,
            378, 379, 380, 381, 382, 383, 384, 385, 386, 387, 388, 389, 390, 391, 392, 393, 394, 395, 396, 397,
            398, 399, 400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410, 411, 412, 413, 415, 416, 417, 418,
            419, 420, 421, 422, 423, 424, 425, 426, 427, 428, 429, 430, 431, 432, 450, 451, 452, 453, 454, 455,
            456, 457, 458, 459, 460, 461, 462, 463, 464, 465, 466, 467, 468, 469, 470, 472, 474, 475, 476, 477,
            478, 479, 480, 481, 482, 483, 484, 485, 488, 489, 490, 491, 492, 493,
    };

    private static volatile List<EmojiItem> cachedSysfaces;
    private static volatile List<EmojiItem> cachedEmojis;
    private static volatile Map<Integer, String> emojiCharMap;

    public static int getEmojiDrawableRes(Context context, int faceId) {
        if (context == null || isEmoji(faceId)) return 0;
        try {
            return context.getResources().getIdentifier("face_" + faceId, "drawable", context.getPackageName());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static boolean isEmoji(int id) {
        return id >= 1000;
    }

    public static String getEmojiChar(int id) {
        if (emojiCharMap != null && emojiCharMap.containsKey(id)) {
            return emojiCharMap.get(id);
        }
        try {
            return new String(Character.toChars(id));
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static List<EmojiItem> getAllSupportedSysfaces() {
        return getAllSupportedSysfaces(null);
    }

    public static synchronized List<EmojiItem> getAllSupportedSysfaces(Context context) {
        if (cachedSysfaces != null) {
            if (context == null || (!cachedSysfaces.isEmpty() && !cachedSysfaces.get(0).getName().isEmpty())) {
                return cachedSysfaces;
            }
        }
        List<EmojiItem> list = new ArrayList<>(ALL_OFFICIAL_FACE_IDS.length);

        if (context != null) {
            try (InputStream is = context.getAssets().open("qq_official_sysfaces.json")) {
                byte[] buf = new byte[is.available()];
                is.read(buf);
                String jsonStr = new String(buf, StandardCharsets.UTF_8);
                JSONArray array = new JSONArray(jsonStr);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    int id = obj.getInt("id");
                    String name = obj.optString("name", "");
                    list.add(new EmojiItem(id, name, EmojiItem.TYPE_SYSFACE, null));
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "从 assets 加载官方小黄脸异常", t);
            }
        }

        if (list.isEmpty()) {
            for (int id : ALL_OFFICIAL_FACE_IDS) {
                list.add(new EmojiItem(id, "", EmojiItem.TYPE_SYSFACE, null));
            }
        }

        cachedSysfaces = Collections.unmodifiableList(list);
        return cachedSysfaces;
    }

    public static List<EmojiItem> getAllSupportedEmojis() {
        return getAllSupportedEmojis(null);
    }

    public static synchronized List<EmojiItem> getAllSupportedEmojis(Context context) {
        if (cachedEmojis != null) {
            if (context == null || cachedEmojis.size() > 10) {
                return cachedEmojis;
            }
        }
        List<EmojiItem> list = new ArrayList<>();
        Map<Integer, String> map = new HashMap<>();

        if (context != null) {
            try (InputStream is = context.getAssets().open("all_phone_emojis.json")) {
                byte[] buf = new byte[is.available()];
                is.read(buf);
                String jsonStr = new String(buf, StandardCharsets.UTF_8);
                JSONArray array = new JSONArray(jsonStr);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    int id = obj.getInt("id");
                    String ch = obj.getString("char");
                    map.put(id, ch);
                    list.add(new EmojiItem(id, "", EmojiItem.TYPE_EMOJI, ch));
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "从 assets 加载全量 1354 Emoji 异常", t);
            }
        }

        if (list.isEmpty()) {
            int[] fallback = new int[]{128077, 128293, 127881, 128175, 127817, 128514, 128522, 128540, 128557, 129315};
            for (int id : fallback) {
                String ch = new String(Character.toChars(id));
                map.put(id, ch);
                list.add(new EmojiItem(id, "", EmojiItem.TYPE_EMOJI, ch));
            }
        }

        emojiCharMap = Collections.unmodifiableMap(map);
        cachedEmojis = Collections.unmodifiableList(list);
        return cachedEmojis;
    }

    public static List<EmojiGroup> getDefaultGroups() {
        List<EmojiGroup> groups = new ArrayList<>();
        // 方案 1：点赞大队 (赞 76, 胜利 79, 爱心 66, 微笑 14, 握手 78)
        groups.add(new EmojiGroup(
                "preset_thumbs_up",
                "点赞大队",
                Arrays.asList(76, 79, 66, 14, 78),
                100
        ));
        // 方案 2：整蛊搞笑 (滑稽 178, 笑哭 12, 给你一拳 474, 不是吧 476, 拜谢 297)
        groups.add(new EmojiGroup(
                "preset_funny",
                "整蛊搞笑",
                Arrays.asList(178, 12, 474, 476, 297),
                100
        ));
        // 方案 3：精选 Emoji (👍, 🔥, 🎉, 💯, 🍉)
        groups.add(new EmojiGroup(
                "preset_emoji_mix",
                "精选Emoji",
                Arrays.asList(128077, 128293, 127881, 128175, 127817),
                100
        ));
        return groups;
    }
}
