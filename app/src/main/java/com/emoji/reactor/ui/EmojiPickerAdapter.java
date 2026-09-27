package com.emoji.reactor.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.emoji.reactor.R;
import com.emoji.reactor.model.DefaultPresets;
import com.emoji.reactor.model.EmojiItem;
import com.emoji.reactor.model.GroupEmojiItem;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 全量小黄脸 + 原生 Emoji 选择适配器（通用架构版，支持动态新表情 NEW 角标）
 */
public class EmojiPickerAdapter extends RecyclerView.Adapter<EmojiPickerAdapter.ViewHolder> {

    public static final int MAX_EMOJI_LIMIT = 20;

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int currentCount);
    }

    private final List<EmojiItem> displayList = new ArrayList<>();
    private final List<GroupEmojiItem> currentSelectedList;
    private final Set<String> selectedKeySet = new HashSet<>();
    private final OnSelectionChangedListener changeListener;

    public EmojiPickerAdapter(List<EmojiItem> initialEmojis, List<GroupEmojiItem> currentSelected, OnSelectionChangedListener listener) {
        if (initialEmojis != null) {
            this.displayList.addAll(initialEmojis);
        }
        this.currentSelectedList = currentSelected != null ? currentSelected : new ArrayList<>();
        for (GroupEmojiItem item : this.currentSelectedList) {
            if (item != null) {
                selectedKeySet.add(item.getEmojiType() + "_" + item.getEmojiId());
            }
        }
        this.changeListener = listener;
    }

    public void switchList(List<EmojiItem> newList) {
        displayList.clear();
        if (newList != null) {
            displayList.addAll(newList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_emoji_picker, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EmojiItem item = displayList.get(position);
        Context ctx = holder.itemView.getContext();
        String emojiId = item.getRawEmojiId();
        long emojiType = item.getRawEmojiType();
        String key = emojiType + "_" + emojiId;

        holder.tvName.setText(item.getName());

        if (item.isEmoji()) {
            // 原生 Emoji 渲染
            holder.ivEmoji.setVisibility(View.GONE);
            holder.tvEmojiChar.setVisibility(View.VISIBLE);
            holder.tvEmojiChar.setText(item.getUnicodeChar());
        } else {
            // QQ 小黄脸图标渲染
            holder.tvEmojiChar.setVisibility(View.GONE);
            holder.ivEmoji.setVisibility(View.VISIBLE);

            // 1. 优先使用官方内置超清素材，绝不被任何外部图片污染或错位覆盖
            int resId = DefaultPresets.getEmojiDrawableRes(ctx, item.getId());
            if (resId != 0) {
                holder.ivEmoji.setImageResource(resId);
            } else {
                // 2. 仅在无内置资源时（如真正的新动态捕获表情），通过 LruCache 内存池安全按需解码
                Bitmap bm = com.emoji.reactor.util.BitmapCacheManager.loadBitmap(item.getImagePath(), 96, 96);
                if (bm != null) {
                    holder.ivEmoji.setImageBitmap(bm);
                } else {
                    holder.ivEmoji.setImageResource(android.R.drawable.ic_menu_gallery);
                }
            }
        }

        // 选中状态高亮与勾选角标
        boolean isSelected = selectedKeySet.contains(key);
        if (isSelected) {
            holder.flContainer.setBackgroundResource(R.drawable.bg_emoji_picker_selected);
            holder.tvBadge.setVisibility(View.VISIBLE);
        } else {
            holder.flContainer.setBackgroundResource(android.R.color.transparent);
            holder.tvBadge.setVisibility(View.GONE);
        }

        // 点击事件：已选则反选取消，未选则添加到方案
        holder.itemView.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos == RecyclerView.NO_POSITION) return;

            if (selectedKeySet.contains(key)) {
                selectedKeySet.remove(key);
                for (int i = 0; i < currentSelectedList.size(); i++) {
                    GroupEmojiItem gItem = currentSelectedList.get(i);
                    if (gItem != null && key.equals(gItem.getEmojiType() + "_" + gItem.getEmojiId())) {
                        currentSelectedList.remove(i);
                        break;
                    }
                }
                notifyItemChanged(currentPos);
                if (changeListener != null) {
                    changeListener.onSelectionChanged(currentSelectedList.size());
                }
            } else {
                if (currentSelectedList.size() >= MAX_EMOJI_LIMIT) {
                    Toast.makeText(ctx, "每套方案最多配置 " + MAX_EMOJI_LIMIT + " 个表情（已达上限）", Toast.LENGTH_SHORT).show();
                    return;
                }
                selectedKeySet.add(key);
                currentSelectedList.add(new GroupEmojiItem(emojiId, emojiType));
                notifyItemChanged(currentPos);
                if (changeListener != null) {
                    changeListener.onSelectionChanged(currentSelectedList.size());
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return displayList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        FrameLayout flContainer;
        ImageView ivEmoji;
        TextView tvEmojiChar;
        TextView tvBadge;
        TextView tvName;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            flContainer = itemView.findViewById(R.id.fl_picker_container);
            ivEmoji = itemView.findViewById(R.id.iv_picker_emoji);
            tvEmojiChar = itemView.findViewById(R.id.tv_picker_emoji_char);
            tvBadge = itemView.findViewById(R.id.tv_selected_badge);
            tvName = itemView.findViewById(R.id.tv_picker_name);
        }
    }
}
