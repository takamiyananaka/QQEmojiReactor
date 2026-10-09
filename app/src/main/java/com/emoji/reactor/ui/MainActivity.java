package com.emoji.reactor.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.emoji.reactor.R;
import com.emoji.reactor.data.ConfigManager;
import com.emoji.reactor.feature.impl.AntiRecallFeature;
import com.emoji.reactor.feature.impl.BatchReactionFeature;
import com.emoji.reactor.feature.impl.CustomAvatarFeature;
import com.emoji.reactor.feature.impl.FlashPicFeature;
import com.emoji.reactor.model.CustomAvatarItem;
import com.emoji.reactor.model.EmojiGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

public class MainActivity extends AppCompatActivity implements EmojiGroupAdapter.OnItemClickListener {

    private SwitchMaterial switchEnable;
    private RecyclerView recyclerView;
    private android.widget.TextView tvEmojiStats;
    private android.widget.ImageView ivMenuIconPreview;
    private android.widget.TextView tvMenuIconStatus;
    private com.google.android.material.button.MaterialButton btnChangeMenuIcon;
    private com.google.android.material.button.MaterialButton btnResetMenuIcon;
    private EmojiGroupAdapter adapter;
    private List<EmojiGroup> groupList;

    private CustomAvatarAdapter avatarAdapter;
    private List<CustomAvatarItem> avatarList;
    private String pendingUinForAvatar = null;

    private final androidx.activity.result.ActivityResultLauncher<Intent> editGroupLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                loadData();
            });

    private final androidx.activity.result.ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    boolean success = ConfigManager.saveCustomMenuIcon(MainActivity.this, uri);
                    if (success) {
                        updateMenuIconUi();
                        Toast.makeText(MainActivity.this, R.string.menu_icon_updated_toast, Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private final androidx.activity.result.ActivityResultLauncher<Intent> cropAvatarLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    loadCustomAvatarsData();
                    Toast.makeText(MainActivity.this, R.string.avatar_updated_toast, Toast.LENGTH_SHORT).show();
                }
            });

    private final androidx.activity.result.ActivityResultLauncher<String> pickAvatarLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                if (uri != null && pendingUinForAvatar != null && !pendingUinForAvatar.isEmpty()) {
                    Intent cropIntent = com.emoji.reactor.ui.crop.CropAvatarActivity.createIntent(MainActivity.this, uri, pendingUinForAvatar);
                    cropAvatarLauncher.launch(cropIntent);
                }
                pendingUinForAvatar = null;
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (getSupportActionBar() != null && versionName != null) {
                getSupportActionBar().setSubtitle("v" + versionName);
            }
        } catch (Exception e) {
            com.emoji.reactor.util.AppLogger.d("MainActivity", "获取versionName安全跳过: " + e.getMessage());
        }

        switchEnable = findViewById(R.id.switch_module_enable);
        recyclerView = findViewById(R.id.recycler_view_groups);
        tvEmojiStats = findViewById(R.id.tv_emoji_stats);
        ivMenuIconPreview = findViewById(R.id.iv_menu_icon_preview);
        tvMenuIconStatus = findViewById(R.id.tv_menu_icon_status);
        btnChangeMenuIcon = findViewById(R.id.btn_change_menu_icon);
        btnResetMenuIcon = findViewById(R.id.btn_reset_menu_icon);
        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fab_add_group);

        // 预先导出默认粉萌图标并同步全量配置与头像镜像至 QQ 媒体目录
        ConfigManager.ensureDefaultMenuIconExported(this);
        ConfigManager.syncFullConfigToFile(this);

        if (btnChangeMenuIcon != null) {
            btnChangeMenuIcon.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        }
        if (btnResetMenuIcon != null) {
            btnResetMenuIcon.setOnClickListener(v -> {
                ConfigManager.resetCustomMenuIcon(MainActivity.this);
                updateMenuIconUi();
                Toast.makeText(MainActivity.this, R.string.menu_icon_reset_toast, Toast.LENGTH_SHORT).show();
            });
        }

        adapter = new EmojiGroupAdapter();
        adapter.setListener(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // 初始化开关状态
        boolean enabled = ConfigManager.isModuleEnabled(this);
        switchEnable.setChecked(enabled);
        switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setModuleEnabled(MainActivity.this, isChecked);
            Toast.makeText(MainActivity.this, isChecked ? R.string.module_enabled_toast : R.string.module_disabled_toast, Toast.LENGTH_SHORT).show();
        });

        // 初始化子功能特征开关状态
        SwitchMaterial switchReaction = findViewById(R.id.switch_feature_reaction);
        SwitchMaterial switchFlashPic = findViewById(R.id.switch_feature_flash_pic);
        SwitchMaterial switchAntiRecall = findViewById(R.id.switch_feature_anti_recall);

        if (switchReaction != null) {
            switchReaction.setChecked(ConfigManager.isFeatureEnabled(this, BatchReactionFeature.KEY, true));
            switchReaction.setOnCheckedChangeListener((btn, isChecked) -> {
                ConfigManager.setFeatureEnabled(MainActivity.this, BatchReactionFeature.KEY, isChecked);
            });
        }

        if (switchFlashPic != null) {
            switchFlashPic.setChecked(ConfigManager.isFeatureEnabled(this, FlashPicFeature.KEY, true));
            switchFlashPic.setOnCheckedChangeListener((btn, isChecked) -> {
                ConfigManager.setFeatureEnabled(MainActivity.this, FlashPicFeature.KEY, isChecked);
            });
        }

        if (switchAntiRecall != null) {
            switchAntiRecall.setChecked(ConfigManager.isFeatureEnabled(this, AntiRecallFeature.KEY, true));
            switchAntiRecall.setOnCheckedChangeListener((btn, isChecked) -> {
                ConfigManager.setFeatureEnabled(MainActivity.this, AntiRecallFeature.KEY, isChecked);
            });
        }

        SwitchMaterial switchCustomAvatar = findViewById(R.id.switch_feature_custom_avatar);
        if (switchCustomAvatar != null) {
            switchCustomAvatar.setChecked(ConfigManager.isFeatureEnabled(this, CustomAvatarFeature.KEY, true));
            switchCustomAvatar.setOnCheckedChangeListener((btn, isChecked) -> {
                ConfigManager.setFeatureEnabled(MainActivity.this, CustomAvatarFeature.KEY, isChecked);
            });
        }

        // 初始化单向自定义头像列表与管理交互
        RecyclerView rvCustomAvatars = findViewById(R.id.recycler_view_custom_avatars);
        if (rvCustomAvatars != null) {
            avatarAdapter = new CustomAvatarAdapter();
            avatarAdapter.setListener(new CustomAvatarAdapter.OnAvatarActionListener() {
                @Override
                public void onChangeAvatar(CustomAvatarItem item) {
                    pendingUinForAvatar = item.getUin();
                    pickAvatarLauncher.launch("image/*");
                }

                @Override
                public void onRestoreAvatar(CustomAvatarItem item) {
                    new MaterialAlertDialogBuilder(MainActivity.this)
                            .setTitle(R.string.dialog_restore_confirm_title)
                            .setMessage(getString(R.string.dialog_restore_confirm_msg, item.getUin()))
                            .setPositiveButton(R.string.dialog_restore_positive_btn, (d, w) -> {
                                ConfigManager.removeCustomAvatar(MainActivity.this, item.getUin());
                                loadCustomAvatarsData();
                                Toast.makeText(MainActivity.this, R.string.avatar_removed_toast, Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }

                @Override
                public void onToggleAvatar(CustomAvatarItem item, boolean isChecked) {
                    ConfigManager.setCustomAvatarEnabled(MainActivity.this, item.getUin(), isChecked);
                }
            });
            rvCustomAvatars.setLayoutManager(new LinearLayoutManager(this));
            rvCustomAvatars.setAdapter(avatarAdapter);
        }

        com.google.android.material.button.MaterialButton btnAddAvatar = findViewById(R.id.btn_add_custom_avatar);
        if (btnAddAvatar != null) {
            btnAddAvatar.setOnClickListener(v -> showAddAvatarDialog());
        }

        // 点击新建方案
        fabAdd.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, EditGroupActivity.class);
            editGroupLauncher.launch(intent);
        });

        loadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        groupList = ConfigManager.loadGroups(this);
        adapter.setData(groupList);

        loadCustomAvatarsData();

        if (tvEmojiStats != null) {
            int officialCount = com.emoji.reactor.data.HybridEmojiRepository.getOfficialFaceCount();
            int dynamicCount = com.emoji.reactor.data.HybridEmojiRepository.getDynamicallyCapturedCount(this);
            tvEmojiStats.setText("已收录官方表情: " + officialCount + " | 动态同步: " + dynamicCount);
        }

        updateMenuIconUi();
    }

    private void loadCustomAvatarsData() {
        if (avatarAdapter != null) {
            avatarList = ConfigManager.loadCustomAvatars(this);
            avatarAdapter.setData(avatarList);
        }
    }

    private void showAddAvatarDialog() {
        android.widget.EditText etUin = new android.widget.EditText(this);
        etUin.setHint(R.string.dialog_input_uin_hint);
        etUin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        etUin.setPadding(pad, pad, pad, pad);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_input_uin_title)
                .setView(etUin)
                .setPositiveButton(R.string.dialog_choose_image_btn, (dialog, which) -> {
                    String uin = etUin.getText().toString().trim();
                    if (uin.isEmpty()) {
                        Toast.makeText(MainActivity.this, R.string.dialog_input_uin_empty_toast, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pendingUinForAvatar = uin;
                    pickAvatarLauncher.launch("image/*");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateMenuIconUi() {
        if (ivMenuIconPreview != null) {
            android.graphics.Bitmap bm = ConfigManager.getCurrentMenuIconBitmap(this);
            if (bm != null) {
                ivMenuIconPreview.setImageBitmap(bm);
            }
        }
        boolean hasCustom = ConfigManager.hasCustomMenuIcon(this);
        if (tvMenuIconStatus != null) {
            tvMenuIconStatus.setText(hasCustom ? R.string.menu_icon_customized : R.string.menu_icon_default);
        }
        if (btnResetMenuIcon != null) {
            btnResetMenuIcon.setVisibility(hasCustom ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    @Override
    public void onItemClick(EmojiGroup group) {
        Intent intent = new Intent(this, EditGroupActivity.class);
        intent.putExtra(EditGroupActivity.EXTRA_GROUP, group);
        editGroupLauncher.launch(intent);
    }

    @Override
    public void onDeleteClick(EmojiGroup group) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(getString(R.string.confirm_delete_msg, group.getName()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    groupList.remove(group);
                    ConfigManager.saveGroups(MainActivity.this, groupList);
                    adapter.setData(groupList);
                    Toast.makeText(MainActivity.this, R.string.deleted_toast, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.action_about).setIcon(android.R.drawable.ic_menu_info_details).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.about_title)
                    .setMessage(getString(R.string.xposed_desc) + "\n\n" + getString(R.string.about_license))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
