package com.emoji.reactor.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.emoji.reactor.R;
import com.emoji.reactor.data.ConfigManager;
import com.emoji.reactor.data.HybridEmojiRepository;
import com.emoji.reactor.model.EmojiGroup;
import com.emoji.reactor.model.EmojiItem;
import com.emoji.reactor.model.GroupEmojiItem;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class EditGroupActivity extends AppCompatActivity {

    public static final String EXTRA_GROUP = "extra_emoji_group";
    public static final int MAX_EMOJI_LIMIT = 20;

    private EmojiGroup currentGroup;
    private boolean isNew = false;

    private TextInputEditText etName;
    private TextView tvDelayVal;
    private Slider sliderDelay;
    private TextView tvSelectedTitle;
    private RecyclerView rvSelected;
    private SelectedEmojiAdapter selectedAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_group);

        Toolbar toolbar = findViewById(R.id.toolbar_edit);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etName = findViewById(R.id.et_group_name);
        tvDelayVal = findViewById(R.id.tv_delay_val);
        sliderDelay = findViewById(R.id.slider_delay);
        tvSelectedTitle = findViewById(R.id.tv_selected_title);
        rvSelected = findViewById(R.id.recycler_selected_emojis);
        MaterialButton btnAdd = findViewById(R.id.btn_add_emoji);
        ExtendedFloatingActionButton fabSave = findViewById(R.id.fab_save_group);

        // 获取传递的方案对象
        currentGroup = (EmojiGroup) getIntent().getSerializableExtra(EXTRA_GROUP);
        if (currentGroup == null) {
            isNew = true;
            currentGroup = new EmojiGroup();
            currentGroup.setName("我的自定义方案");
            currentGroup.setDelayMs(100);
        }

        etName.setText(currentGroup.getName());
        int delay = currentGroup.getDelayMs();
        if (delay < 50) delay = 50;
        if (delay > 300) delay = 300;
        sliderDelay.setValue(delay);
        tvDelayVal.setText(delay + " ms");

        sliderDelay.addOnChangeListener((slider, value, fromUser) -> {
            int val = (int) value;
            tvDelayVal.setText(val + " ms");
        });

        // 已选表情列表适配器
        selectedAdapter = new SelectedEmojiAdapter();
        // 严格截断到 20 个
        List<GroupEmojiItem> initialList = new ArrayList<>(currentGroup.getItems());
        if (initialList.size() > MAX_EMOJI_LIMIT) {
            initialList = initialList.subList(0, MAX_EMOJI_LIMIT);
        }
        selectedAdapter.setData(initialList);
        selectedAdapter.setListener(position -> {
            selectedAdapter.removeAt(position);
            updateTitleCount();
        });

        rvSelected.setLayoutManager(new LinearLayoutManager(this));
        rvSelected.setAdapter(selectedAdapter);
        updateTitleCount();

        // 弹出表情挑选框
        btnAdd.setOnClickListener(v -> {
            if (selectedAdapter.getItemCount() >= MAX_EMOJI_LIMIT) {
                Toast.makeText(EditGroupActivity.this, getString(R.string.emoji_limit_reached, MAX_EMOJI_LIMIT), Toast.LENGTH_SHORT).show();
                return;
            }
            showEmojiPickerDialog();
        });

        // 保存方案
        fabSave.setOnClickListener(v -> saveAndExit());
    }

    private void updateTitleCount() {
        int count = selectedAdapter.getItemCount();
        tvSelectedTitle.setText("已配置表情序列 (" + count + "/" + MAX_EMOJI_LIMIT + "，点击右侧删除)");
    }

    private void showEmojiPickerDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_emoji_picker, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_picker_title);
        RecyclerView rvPicker = dialogView.findViewById(R.id.recycler_picker);
        MaterialButton btnTabSysface = dialogView.findViewById(R.id.btn_tab_sysface);
        MaterialButton btnTabEmoji = dialogView.findViewById(R.id.btn_tab_emoji);

        rvPicker.setLayoutManager(new GridLayoutManager(this, 5));

        final List<GroupEmojiItem> currentWorkingList = new ArrayList<>(selectedAdapter.getData());
        tvTitle.setText("选择表情 (已选 " + currentWorkingList.size() + "/" + MAX_EMOJI_LIMIT + ")");

        // 每次打开弹窗强行失效旧缓存，确保刚从 QQ 抓到的表情即刻上屏
        HybridEmojiRepository.invalidateCache();
        List<EmojiItem> initialSysfaces = HybridEmojiRepository.getMergedSysfaces(this);
        btnTabSysface.setText("QQ小黄脸 (" + initialSysfaces.size() + ")");

        EmojiPickerAdapter pickerAdapter = new EmojiPickerAdapter(initialSysfaces, currentWorkingList, currentCount -> {
            tvTitle.setText("选择表情 (已选 " + currentCount + "/" + MAX_EMOJI_LIMIT + ")");
        });
        rvPicker.setAdapter(pickerAdapter);

        // 分类 1：切换到小黄脸 (全量融合)
        btnTabSysface.setOnClickListener(v -> {
            HybridEmojiRepository.invalidateCache();
            List<EmojiItem> sysfaces = HybridEmojiRepository.getMergedSysfaces(EditGroupActivity.this);
            pickerAdapter.switchList(sysfaces);
            btnTabSysface.setText("QQ小黄脸 (" + sysfaces.size() + ")");
            btnTabSysface.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.primary));
            btnTabSysface.setTextColor(androidx.core.content.ContextCompat.getColor(this, android.R.color.white));
            btnTabEmoji.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, android.R.color.transparent));
            btnTabEmoji.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.primary));
        });

        // 分类 2：切换到原生 Emoji (全量)
        btnTabEmoji.setOnClickListener(v -> {
            List<EmojiItem> emojis = HybridEmojiRepository.getAllEmojis(EditGroupActivity.this);
            pickerAdapter.switchList(emojis);
            btnTabEmoji.setText("全量 Emoji (" + emojis.size() + ")");
            btnTabEmoji.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.primary));
            btnTabEmoji.setTextColor(androidx.core.content.ContextCompat.getColor(this, android.R.color.white));
            btnTabSysface.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, android.R.color.transparent));
            btnTabSysface.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.primary));
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("完成", (d, which) -> {
                    selectedAdapter.setData(currentWorkingList);
                    updateTitleCount();
                })
                .setNegativeButton("取消", null)
                .create();

        dialog.show();
    }

    private void saveAndExit() {
        String name = etName.getText() != null ? etName.getText().toString().trim() : "";
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入方案名称", Toast.LENGTH_SHORT).show();
            return;
        }

        List<GroupEmojiItem> emojis = selectedAdapter.getData();
        if (emojis.isEmpty()) {
            Toast.makeText(this, "请至少添加一个表情", Toast.LENGTH_SHORT).show();
            return;
        }

        if (emojis.size() > MAX_EMOJI_LIMIT) {
            emojis = emojis.subList(0, MAX_EMOJI_LIMIT);
        }

        currentGroup.setName(name);
        currentGroup.setDelayMs((int) sliderDelay.getValue());
        currentGroup.setItems(emojis);

        List<EmojiGroup> groups = ConfigManager.loadGroups(this);
        if (isNew) {
            groups.add(currentGroup);
        } else {
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).getId().equals(currentGroup.getId())) {
                    groups.set(i, currentGroup);
                    break;
                }
            }
        }

        ConfigManager.saveGroups(this, groups);
        Toast.makeText(this, "方案已保存", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}
