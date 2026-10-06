package com.emoji.reactor.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.Objects;

/**
 * 个人单向自定义头像配置项 (CustomAvatarItem)
 */
public class CustomAvatarItem implements Serializable {

    private String uin;
    private boolean enabled;
    private String imagePath;
    private String imageBase64;
    private long lastModified;

    public CustomAvatarItem() {
        this.uin = "";
        this.enabled = true;
        this.imagePath = "";
        this.imageBase64 = "";
        this.lastModified = System.currentTimeMillis();
    }

    public CustomAvatarItem(String uin, boolean enabled, String imagePath) {
        this.uin = uin != null ? uin.trim() : "";
        this.enabled = enabled;
        this.imagePath = imagePath != null ? imagePath : "";
        this.imageBase64 = "";
        this.lastModified = System.currentTimeMillis();
    }

    public String getUin() {
        return uin;
    }

    public void setUin(String uin) {
        this.uin = uin != null ? uin.trim() : "";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath != null ? imagePath : "";
    }

    public String getImageBase64() {
        return imageBase64 != null ? imageBase64 : "";
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64 != null ? imageBase64 : "";
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("uin", uin);
            obj.put("enabled", enabled);
            obj.put("imagePath", imagePath);
            obj.put("lastModified", lastModified);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    public static CustomAvatarItem fromJson(JSONObject obj) {
        if (obj == null) return null;
        String uin = obj.optString("uin", "");
        boolean enabled = obj.optBoolean("enabled", true);
        String imagePath = obj.optString("imagePath", "");
        long lastModified = obj.optLong("lastModified", System.currentTimeMillis());

        CustomAvatarItem item = new CustomAvatarItem(uin, enabled, imagePath);
        item.setLastModified(lastModified);
        return item;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CustomAvatarItem that = (CustomAvatarItem) o;
        return Objects.equals(uin, that.uin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uin);
    }
}
