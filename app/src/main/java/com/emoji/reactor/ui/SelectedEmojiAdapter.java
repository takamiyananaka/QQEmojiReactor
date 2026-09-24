package com.emoji.reactor.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.emoji.reactor.R;
import com.emoji.reactor.model.DefaultPresets;
import com.emoji.reactor.model.GroupEmojiItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 方案编辑页已选表情列表适配器（通用架构版，无缝适配动态表情与原生 Emoji）
 */
public class SelectedEmojiAdapter extends RecyclerView.Adapter<SelectedEmojiAdapter.ViewHolder> {

    public interface OnRemoveClickListener {
        void onRemove(int position);
    }

    private final List<GroupEmojiItem> list = new ArrayList<>();
    private OnRemoveClickListener listener;

    public void setListener(OnRemoveClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<GroupEmojiItem> newList) {
        list.clear();
        if (newList != null) {
            list.addAll(newList);
        }
        notifyDataSetChanged();
    }

    public List<GroupEmojiItem> getData() {
        return new ArrayList<>(list);
    }

    public void addEmoji(GroupEmojiItem item) {
        if (item != null) {
            list.add(item);
            notifyItemInserted(list.size() - 1);
        }
    }

    public void removeAt(int position) {
        if (position >= 0 && position < list.size()) {
            list.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, list.size() - position);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_selected_emoji, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GroupEmojiItem item = list.get(position);
        Context ctx = holder.itemView.getContext();

        holder.tvOrder.setText((position + 1) + ".");

        if (item.isEmoji()) {
            holder.ivEmoji.setVisibility(View.GONE);
            holder.tvEmojiChar.setVisibility(View.VISIBLE);
            int intId = item.getLegacyIntId();
            String ch = (intId > 0) ? DefaultPresets.getEmojiChar(intId) : "";
            if (ch.isEmpty()) {
                try {
                    ch = new String(Character.toChars(Integer.parseInt(item.getEmojiId())));
                } catch (Throwable ignored) {
                }
            }
            holder.tvEmojiChar.setText(ch);
        } else {
            holder.tvEmojiChar.setVisibility(View.GONE);
            holder.ivEmoji.setVisibility(View.VISIBLE);

            boolean loaded = false;
            // 1. 尝试从模块私有克隆目录加载动态位图
            File privateImg = new File(ctx.getFilesDir(), "live_faces/face_" + item.getEmojiId() + ".png");
            if (privateImg.exists() && privateImg.canRead()) {
                Bitmap bm = BitmapFactory.decodeFile(privateImg.getAbsolutePath());
                if (bm != null) {
                    holder.ivEmoji.setImageBitmap(bm);
                    loaded = true;
                }
            }

            // 2. 尝试从 QQ 外部媒体目录中转加载
            if (!loaded) {
                File mediaImg = new File("/sdcard/Android/media/com.tencent.mobileqq/live_emojis/face_" + item.getEmojiId() + ".png");
                if (mediaImg.exists() && mediaImg.canRead()) {
                    Bitmap bm = BitmapFactory.decodeFile(mediaImg.getAbsolutePath());
                    if (bm != null) {
                        holder.ivEmoji.setImageBitmap(bm);
                        loaded = true;
                    }
                }
            }

            // 3. 兜底内置官方高清 Drawable
            if (!loaded) {
                int resId = DefaultPresets.getEmojiDrawableRes(ctx, item.getLegacyIntId());
                if (resId != 0) {
                    holder.ivEmoji.setImageResource(resId);
                } else {
                    holder.ivEmoji.setImageResource(android.R.drawable.ic_menu_gallery);
                }
            }
        }

        holder.btnRemove.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos != RecyclerView.NO_POSITION && listener != null) {
                listener.onRemove(currentPos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvOrder;
        ImageView ivEmoji;
        TextView tvEmojiChar;
        ImageButton btnRemove;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvOrder = itemView.findViewById(R.id.tv_order_index);
            ivEmoji = itemView.findViewById(R.id.iv_selected_emoji);
            tvEmojiChar = itemView.findViewById(R.id.tv_selected_emoji_char);
            btnRemove = itemView.findViewById(R.id.btn_remove_selected_emoji);
        }
    }
}
