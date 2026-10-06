package com.emoji.reactor.engine;

import com.emoji.reactor.model.GroupEmojiItem;
import com.emoji.reactor.util.AppLogger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 表情防取消去重过滤器（通用架构版）
 * 仅识别【当前登录账号本人】已贴过的表情，支持任意原生字符串 emojiId 与 emojiType
 * 绝不把群友贴的表情误当成自己贴的
 */
public class ReactionDeduplicator {

    private static final String TAG = "QQEmojiReactor_Dedup";

    /**
     * 过滤待贴表情条目列表（通用实体版）
     */
    public static List<GroupEmojiItem> filterItemsToApply(Object msgRecord, List<GroupEmojiItem> targetItems) {
        if (targetItems == null || targetItems.isEmpty()) {
            return new ArrayList<>();
        }

        if (msgRecord == null) {
            return new ArrayList<>(targetItems);
        }

        try {
            Set<String> selfLikedIds = extractSelfLikedEmojiIds(msgRecord);
            if (selfLikedIds.isEmpty()) {
                return new ArrayList<>(targetItems);
            }

            List<GroupEmojiItem> finalToApply = new ArrayList<>();
            for (GroupEmojiItem item : targetItems) {
                if (item == null) continue;
                String idStr = item.getEmojiId();
                if (selfLikedIds.contains(idStr)) {
                    AppLogger.d(TAG, "本人已贴过表情 ID " + idStr + "，自动跳过以防取消");
                } else {
                    finalToApply.add(item);
                }
            }
            return finalToApply;
        } catch (Throwable t) {
            AppLogger.e(TAG, "去重算法执行异常，平滑降级为全量发送", t);
            return new ArrayList<>(targetItems);
        }
    }

    /**
     * 兼容旧版整型 ID 过滤接口
     */
    public static List<Integer> filterEmojisToApply(Object msgRecord, List<Integer> targetFaces) {
        if (targetFaces == null || targetFaces.isEmpty()) {
            return new ArrayList<>();
        }
        List<GroupEmojiItem> items = new ArrayList<>();
        for (Integer id : targetFaces) {
            if (id != null) items.add(new GroupEmojiItem(id));
        }
        List<GroupEmojiItem> filtered = filterItemsToApply(msgRecord, items);
        List<Integer> result = new ArrayList<>();
        for (GroupEmojiItem it : filtered) {
            result.add(it.getLegacyIntId());
        }
        return result;
    }

    /**
     * 仅提取本人亲自点赞/贴过的表情 ID 集合 (字符串格式，包含小黄脸与原生 Emoji)
     */
    public static Set<String> extractSelfLikedEmojiIds(Object msgRecord) {
        Set<String> selfLikes = new HashSet<>();
        if (msgRecord == null) return selfLikes;

        Class<?> clazz = msgRecord.getClass();
        while (clazz != null && clazz != Object.class) {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                String fieldName = field.getName().toLowerCase();
                if (fieldName.contains("emoji") || fieldName.contains("like") || fieldName.contains("reaction")) {
                    try {
                        field.setAccessible(true);
                        Object val = field.get(msgRecord);
                        if (val instanceof Collection) {
                            for (Object item : (Collection<?>) val) {
                                if (item == null) continue;
                                if (isLikelySelfLiked(item)) {
                                    String emojiId = extractEmojiIdFromItem(item);
                                    if (emojiId != null && !emojiId.isEmpty()) {
                                        selfLikes.add(emojiId);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        AppLogger.d(TAG, "extractSelfLikedEmojiIds 反射安全跳过: " + e.getMessage());
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return selfLikes;
    }

    /**
     * 判断一个表情回应项是否由当前用户本人点赞
     */
    public static boolean isLikelySelfLiked(Object item) {
        if (item == null) return false;
        Class<?> c = item.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("self") || name.contains("clicked") || name.contains("isme") || name.contains("byme")) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(item);
                        if (val instanceof Boolean) {
                            return (Boolean) val;
                        }
                    } catch (Exception e) {
                        AppLogger.d(TAG, "isLikelySelfLiked 反射安全跳过: " + e.getMessage());
                    }
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    public static String extractEmojiIdFromItem(Object item) {
        if (item == null) return null;
        if (item instanceof String) return (String) item;
        if (item instanceof Number) return String.valueOf(item);

        Class<?> c = item.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("faceid") || name.contains("emojiid") || name.equals("id")) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(item);
                        if (val != null) {
                            return String.valueOf(val);
                        }
                    } catch (Exception e) {
                        AppLogger.d(TAG, "extractEmojiIdFromItem 反射安全跳过: " + e.getMessage());
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
