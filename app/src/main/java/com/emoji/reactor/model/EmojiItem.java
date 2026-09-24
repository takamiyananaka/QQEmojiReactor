package com.emoji.reactor.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * 表情项数据模型
 * 支持 QQ 官方系统小黄脸 (TYPE_SYSFACE) 与 原生系统 Emoji (TYPE_EMOJI)
 * 支持静态本地 Drawable 资源与 QQ 动态抓取的本地路径
 */
public class EmojiItem implements Serializable {
    private static final long serialVersionUID = 3L;

    public static final int TYPE_SYSFACE = 1; // 官方系统小黄脸
    public static final int TYPE_EMOJI = 2;   // 原生系统 Emoji

    private final int id;               // 表情唯一整型标识（兼容旧代码）
    private final String rawEmojiId;    // QQ 原生 ID 字符串（如 "76"、"178"、"mix-xxx"）
    private final long rawEmojiType;    // QQ 原生 Type (1L: 小黄脸/大黄脸, 2L: Emoji, 其他)
    private final String name;         // 表情描述
    private final int type;            // 表情类型 (1 或 2)
    private final String unicodeChar;  // Emoji 字符（如 "😊"），小黄脸为 null
    private final String imagePath;    // QQ 动态抓取的本地高清图片路径（可选）
    private final boolean isDynamic;   // 是否为 QQ 运行时动态捕获的最新表情

    public EmojiItem(int id, String name) {
        this(id, name, TYPE_SYSFACE, null, null, false);
    }

    public EmojiItem(int id, String name, int type, String unicodeChar) {
        this(id, name, type, unicodeChar, null, false);
    }

    public EmojiItem(int id, String name, int type, String unicodeChar, String imagePath) {
        this(id, name, type, unicodeChar, imagePath, false);
    }

    public EmojiItem(int id, String name, int type, String unicodeChar, String imagePath, boolean isDynamic) {
        this.id = id;
        this.rawEmojiId = String.valueOf(id);
        this.rawEmojiType = (type == TYPE_EMOJI || id >= 1000) ? 2L : 1L;
        this.name = name != null ? name : "";
        this.type = type;
        this.unicodeChar = unicodeChar;
        this.imagePath = imagePath;
        this.isDynamic = isDynamic;
    }

    public EmojiItem(String rawEmojiId, long rawEmojiType, String name, String unicodeChar, String imagePath, boolean isDynamic) {
        int parsedId = 0;
        try {
            parsedId = Integer.parseInt(rawEmojiId);
        } catch (Throwable ignored) {
            parsedId = rawEmojiId.hashCode();
        }
        this.id = parsedId;
        this.rawEmojiId = rawEmojiId != null ? rawEmojiId : String.valueOf(parsedId);
        this.rawEmojiType = rawEmojiType;
        this.name = name != null ? name : "";
        this.type = (rawEmojiType == 2L) ? TYPE_EMOJI : TYPE_SYSFACE;
        this.unicodeChar = unicodeChar;
        this.imagePath = imagePath;
        this.isDynamic = isDynamic;
    }

    public int getId() {
        return id;
    }

    public String getRawEmojiId() {
        return rawEmojiId != null ? rawEmojiId : String.valueOf(id);
    }

    public long getRawEmojiType() {
        return rawEmojiType > 0 ? rawEmojiType : ((type == TYPE_EMOJI || id >= 1000) ? 2L : 1L);
    }

    public String getName() {
        return name;
    }

    public int getType() {
        return type;
    }

    public String getImagePath() {
        return imagePath;
    }

    public boolean isDynamic() {
        return isDynamic;
    }

    public String getUnicodeChar() {
        if (unicodeChar != null) return unicodeChar;
        if (type == TYPE_EMOJI) {
            try {
                return new String(Character.toChars(id));
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    public boolean isEmoji() {
        return type == TYPE_EMOJI || id >= 1000;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmojiItem emojiItem = (EmojiItem) o;
        return id == emojiItem.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "[" + (isEmoji() ? "Emoji" : "Face") + " " + id + "] " + name;
    }
}
