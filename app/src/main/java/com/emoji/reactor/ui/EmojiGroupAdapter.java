package com.emoji.reactor.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.emoji.reactor.R;
import com.emoji.reactor.model.DefaultPresets;
import com.emoji.reactor.model.EmojiGroup;
import com.emoji.reactor.model.GroupEmojiItem;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 方案卡片列表适配器（全色彩模式兼容 + 显式编辑按钮）
 */
public class EmojiGroupAdapter extends RecyclerView.Adapter<EmojiGroupAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(EmojiGroup group);
        void onDeleteClick(EmojiGroup group);
    }

    private final List<EmojiGroup> list = new ArrayList<>();
    private OnItemClickListener listener;

    public void setListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<EmojiGroup> newList) {
        list.clear();
        if (newList != null) {
            list.addAll(newList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_emoji_group, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EmojiGroup group = list.get(position);
        Context ctx = holder.itemView.getContext();

        holder.tvName.setText(group.getName());
        holder.tvDelay.setText(group.getDelayMs() + "ms 间隔");

        List<GroupEmojiItem> items = group.getItems();
        holder.tvCount.setText("已配置 " + items.size() + "/20 个表情");

        // 动态排布小黄脸图标流
        holder.llPreview.removeAllViews();
        int previewLimit = Math.min(items.size(), 20);
        int iconSize = dp2px(ctx, 32);
        int margin = dp2px(ctx, 5);

        for (int i = 0; i < previewLimit; i++) {
            GroupEmojiItem item = items.get(i);
            if (item == null) continue;
            if (item.isEmoji()) {
                TextView tv = new TextView(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(iconSize, iconSize);
                lp.rightMargin = margin;
                tv.setLayoutParams(lp);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextSize(20);
                String ch = DefaultPresets.getEmojiChar(item.getLegacyIntId());
                if (ch.isEmpty()) {
                    try {
                        ch = new String(Character.toChars(Integer.parseInt(item.getEmojiId())));
                    } catch (Throwable ignored) {
                    }
                }
                tv.setText(ch);
                holder.llPreview.addView(tv);
            } else {
                ImageView iv = new ImageView(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(iconSize, iconSize);
                lp.rightMargin = margin;
                iv.setLayoutParams(lp);

                boolean loaded = false;
                File privateImg = new File(ctx.getFilesDir(), "live_faces/face_" + item.getEmojiId() + ".png");
                if (privateImg.exists() && privateImg.canRead()) {
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(privateImg.getAbsolutePath());
                    if (bm != null) {
                        iv.setImageBitmap(bm);
                        loaded = true;
                    }
                }
                if (!loaded) {
                    int resId = DefaultPresets.getEmojiDrawableRes(ctx, item.getLegacyIntId());
                    if (resId != 0) {
                        iv.setImageResource(resId);
                    } else {
                        iv.setImageResource(android.R.drawable.ic_menu_gallery);
                    }
                }
                holder.llPreview.addView(iv);
            }
        }

        // 无论是点击卡片本身、点击编辑按钮还是点击内容区，都进入编辑
        View.OnClickListener editClickListener = v -> {
            if (listener != null) listener.onItemClick(group);
        };

        holder.itemView.setOnClickListener(editClickListener);
        holder.btnEdit.setOnClickListener(editClickListener);
        holder.llBody.setOnClickListener(editClickListener);

        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteClick(group);
        });
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    private static int dp2px(Context context, float dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View llBody;
        TextView tvName;
        TextView tvDelay;
        MaterialButton btnEdit;
        ImageButton btnDelete;
        LinearLayout llPreview;
        TextView tvCount;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            llBody = itemView.findViewById(R.id.ll_card_body);
            tvName = itemView.findViewById(R.id.tv_group_name);
            tvDelay = itemView.findViewById(R.id.tv_delay_info);
            btnEdit = itemView.findViewById(R.id.btn_edit_group);
            btnDelete = itemView.findViewById(R.id.btn_delete_group);
            llPreview = itemView.findViewById(R.id.ll_emojis_preview_images);
            tvCount = itemView.findViewById(R.id.tv_emoji_count);
        }
    }
}
