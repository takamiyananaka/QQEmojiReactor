package com.emoji.reactor.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * 方案内具体表情条目（通用化模型）
 * 彻底解耦于纯 int 假定，直接承载原生 emojiId (String) 与 emojiType (long)
 */
public class GroupEmojiItem implements Serializable {
    private static final long serialVersionUID = 2L;

    private String emojiId;     // 原生表情 ID（如 "76"、"178"、"128077" 或带前缀的特殊 ID）
    private long emojiType;     // 原生表情类型 (1L: 系统小黄脸/大黄脸, 2L: 原生 Emoji, 其他扩展类型)

    public GroupEmojiItem() {
        this.emojiId = "";
        this.emojiType = 1L;
    }

    public GroupEmojiItem(String emojiId, long emojiType) {
        this.emojiId = emojiId != null ? emojiId.trim() : "";
        this.emojiType = emojiType;
    }

    public GroupEmojiItem(int legacyId) {
        this.emojiId = String.valueOf(legacyId);
        this.emojiType = (legacyId >= 1000) ? 2L : 1L;
    }

    public String getEmojiId() {
        return emojiId != null ? emojiId : "";
    }

    public void setEmojiId(String emojiId) {
        this.emojiId = emojiId != null ? emojiId.trim() : "";
    }

    public long getEmojiType() {
        return emojiType;
    }

    public void setEmojiType(long emojiType) {
        this.emojiType = emojiType;
    }

    /**
     * 兼容旧版数字 ID 读取
     */
    public int getLegacyIntId() {
        try {
            return Integer.parseInt(emojiId);
        } catch (Throwable t) {
            return 0;
        }
    }

    public boolean isEmoji() {
        return emojiType == 2L || getLegacyIntId() >= 1000;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GroupEmojiItem that = (GroupEmojiItem) o;
        return emojiType == that.emojiType && Objects.equals(emojiId, that.emojiId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(emojiId, emojiType);
    }

    @Override
    public String toString() {
        return "GroupEmojiItem{id='" + emojiId + "', type=" + emojiType + "}";
    }
}
