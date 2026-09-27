package com.emoji.reactor.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 用户自定义的表情预设方案
 * 包含方案唯一标识、方案名称、配置的 Face ID 序列以及触发时的调用微间隔（毫秒）
 */
public class EmojiGroup implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;                 // 方案唯一 ID
    private String name;               // 方案名称（如“点赞大队”、“阴阳滑稽”）
    private List<GroupEmojiItem> items;// 通用表情序列条目（支持原生 String emojiId + long emojiType）
    private int delayMs;               // 连贴间隔毫秒数（默认 100ms，安全防风控）

    public EmojiGroup() {
        this.id = UUID.randomUUID().toString();
        this.name = "未命名方案";
        this.items = new ArrayList<>();
        this.delayMs = 100;
    }

    public EmojiGroup(String name, List<Integer> legacyEmojiIds, int delayMs) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.items = new ArrayList<>();
        if (legacyEmojiIds != null) {
            for (Integer fid : legacyEmojiIds) {
                if (fid != null) {
                    this.items.add(new GroupEmojiItem(fid));
                }
            }
        }
        this.delayMs = delayMs > 0 ? delayMs : 100;
    }

    public EmojiGroup(String id, String name, List<Integer> legacyEmojiIds, int delayMs) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.items = new ArrayList<>();
        if (legacyEmojiIds != null) {
            for (Integer fid : legacyEmojiIds) {
                if (fid != null) {
                    this.items.add(new GroupEmojiItem(fid));
                }
            }
        }
        this.delayMs = delayMs > 0 ? delayMs : 100;
    }

    public static EmojiGroup fromItems(String id, String name, List<GroupEmojiItem> items, int delayMs) {
        EmojiGroup g = new EmojiGroup();
        g.setId(id != null ? id : UUID.randomUUID().toString());
        g.setName(name);
        g.setItems(items);
        g.setDelayMs(delayMs > 0 ? delayMs : 100);
        return g;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<GroupEmojiItem> getItems() {
        if (items == null) {
            items = new ArrayList<>();
        }
        return Collections.unmodifiableList(items);
    }

    public void setItems(List<GroupEmojiItem> items) {
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    /**
     * 兼容旧版调用，返回整型 ID 列表
     */
    public List<Integer> getEmojiIds() {
        List<Integer> list = new ArrayList<>();
        if (items != null) {
            for (GroupEmojiItem item : items) {
                if (item != null) {
                    list.add(item.getLegacyIntId());
                }
            }
        }
        return list;
    }

    /**
     * 兼容旧版调用，批量设置整型 ID 列表
     */
    public void setEmojiIds(List<Integer> emojiIds) {
        this.items = new ArrayList<>();
        if (emojiIds != null) {
            for (Integer id : emojiIds) {
                if (id != null) {
                    this.items.add(new GroupEmojiItem(id));
                }
            }
        }
    }

    public int getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(int delayMs) {
        this.delayMs = delayMs;
    }

    /**
     * 向方案追加一个表情条目（通用版）
     */
    public void addEmoji(String emojiId, long emojiType) {
        if (items == null) {
            items = new ArrayList<>();
        }
        items.add(new GroupEmojiItem(emojiId, emojiType));
    }

    /**
     * 向方案追加一个表情 ID（兼容旧版）
     */
    public void addEmoji(int faceId) {
        if (items == null) {
            items = new ArrayList<>();
        }
        items.add(new GroupEmojiItem(faceId));
    }

    /**
     * 移除指定位置的表情
     */
    public void removeEmojiAt(int index) {
        if (items != null && index >= 0 && index < items.size()) {
            items.remove(index);
        }
    }
}
