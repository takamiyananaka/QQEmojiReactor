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
import com.emoji.reactor.model.EmojiGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

public class MainActivity extends AppCompatActivity implements EmojiGroupAdapter.OnItemClickListener {

    private static final int REQUEST_CODE_EDIT = 1001;

    private SwitchMaterial switchEnable;
    private RecyclerView recyclerView;
    private EmojiGroupAdapter adapter;
    private List<EmojiGroup> groupList;

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
        } catch (Throwable ignored) {
        }

        switchEnable = findViewById(R.id.switch_module_enable);
        recyclerView = findViewById(R.id.recycler_view_groups);
        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fab_add_group);

        adapter = new EmojiGroupAdapter();
        adapter.setListener(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // 初始化开关状态
        boolean enabled = ConfigManager.isModuleEnabled(this);
        switchEnable.setChecked(enabled);
        switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigManager.setModuleEnabled(MainActivity.this, isChecked);
            Toast.makeText(MainActivity.this, isChecked ? "已启用贴表情模块" : "已停用贴表情模块", Toast.LENGTH_SHORT).show();
        });

        // 点击新建方案
        fabAdd.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, EditGroupActivity.class);
            startActivityForResult(intent, REQUEST_CODE_EDIT);
        });

        loadData();
    }

    private void loadData() {
        groupList = ConfigManager.loadGroups(this);
        adapter.setData(groupList);
    }

    @Override
    public void onItemClick(EmojiGroup group) {
        Intent intent = new Intent(this, EditGroupActivity.class);
        intent.putExtra(EditGroupActivity.EXTRA_GROUP, group);
        startActivityForResult(intent, REQUEST_CODE_EDIT);
    }

    @Override
    public void onDeleteClick(EmojiGroup group) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要删除方案「" + group.getName() + "」吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    groupList.remove(group);
                    ConfigManager.saveGroups(MainActivity.this, groupList);
                    adapter.setData(groupList);
                    Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_EDIT && resultCode == RESULT_OK) {
            loadData();
        }
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
