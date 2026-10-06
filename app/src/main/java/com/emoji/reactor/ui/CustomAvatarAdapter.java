package com.emoji.reactor.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.emoji.reactor.R;
import com.emoji.reactor.model.CustomAvatarItem;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CustomAvatarAdapter extends RecyclerView.Adapter<CustomAvatarAdapter.ViewHolder> {

    public interface OnAvatarActionListener {
        void onChangeAvatar(CustomAvatarItem item);
        void onRestoreAvatar(CustomAvatarItem item);
        void onToggleAvatar(CustomAvatarItem item, boolean isChecked);
    }

    private final List<CustomAvatarItem> items = new ArrayList<>();
    private OnAvatarActionListener listener;

    public void setListener(OnAvatarActionListener listener) {
        this.listener = listener;
    }

    public void setData(List<CustomAvatarItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_custom_avatar, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CustomAvatarItem item = items.get(position);
        holder.tvUin.setText("QQ: " + item.getUin());
        holder.switchEnable.setOnCheckedChangeListener(null);
        holder.switchEnable.setChecked(item.isEnabled());

        // 加载并渲染圆形头像预览
        if (item.getImagePath() != null && !item.getImagePath().isEmpty()) {
            File f = new File(item.getImagePath());
            if (f.exists() && f.length() > 0) {
                Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm != null) {
                    holder.ivPreview.setImageBitmap(createCircleBitmap(bm));
                } else {
                    holder.ivPreview.setImageResource(R.mipmap.ic_launcher_round);
                }
            } else {
                holder.ivPreview.setImageResource(R.mipmap.ic_launcher_round);
            }
        } else {
            holder.ivPreview.setImageResource(R.mipmap.ic_launcher_round);
        }

        holder.switchEnable.setOnCheckedChangeListener((btn, isChecked) -> {
            if (listener != null) {
                listener.onToggleAvatar(item, isChecked);
            }
        });

        holder.btnChange.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChangeAvatar(item);
            }
        });

        holder.btnRestore.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRestoreAvatar(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivPreview;
        final TextView tvUin;
        final MaterialButton btnChange;
        final MaterialButton btnRestore;
        final SwitchMaterial switchEnable;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivPreview = itemView.findViewById(R.id.iv_custom_avatar_preview);
            tvUin = itemView.findViewById(R.id.tv_avatar_uin);
            btnChange = itemView.findViewById(R.id.btn_change_avatar);
            btnRestore = itemView.findViewById(R.id.btn_restore_avatar);
            switchEnable = itemView.findViewById(R.id.switch_avatar_item_enable);
        }
    }

    private static Bitmap createCircleBitmap(Bitmap source) {
        if (source == null) return null;
        int size = Math.min(source.getWidth(), source.getHeight());
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        float r = size / 2f;
        canvas.drawCircle(r, r, r, paint);
        return output;
    }
}
