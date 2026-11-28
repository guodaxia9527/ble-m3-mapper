package com.guodaxia.blem3mapper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity implements MapperConnection.Listener {
    private static final int INK = Color.rgb(30, 37, 57);
    private static final int MUTED = Color.rgb(101, 111, 134);
    private static final int ACCENT = Color.rgb(75, 88, 219);
    private static final int BACKGROUND = Color.rgb(245, 247, 252);
    private static final String[] BUTTON_KEYS = {"UP", "DOWN", "LEFT", "RIGHT", "CENTER", "PHOTO"};
    private static final String[] BUTTON_LABELS = {"上键", "下键", "左键", "右键", "中键", "第六键"};
    private static final String[] ACTION_LABELS = {"后退", "前进", "上一条字幕", "下一条字幕", "播放 / 暂停", "开关转写文稿", "不执行", "点赞"};
    private static final String PREFS = "mapper";
    private static final String CONFIG_KEY = "profiles_json";
    private static final String PHOTO_TRANSCRIPT_MIGRATED = "photo_transcript_toggle_migrated_v1";
    private static final String ADAPTIVE_CAPTIONS_MIGRATED = "adaptive_caption_seconds_migrated_v2";
    private static final String CONFIG_BEFORE_ADAPTIVE_V2 = "profiles_json_before_caption_seconds_v2";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ButtonEditor[] editors = new ButtonEditor[6];
    private MapperConnection mapper;
    private JSONObject config;
    private JSONArray profiles;
    private int profileIndex;
    private boolean rendering;
    private boolean busy;
    private boolean visible;
    private String connectionUiState;
    private TextView shizukuStatus;
    private TextView runningStatus;
    private TextView detailStatus;
    private TextView highlightStatus;
    private Button overlayPermissionButton;
    private TextView configHint;
    private TextView logText;
    private Button permissionButton;
    private Button startButton;
    private Button stopButton;
    private Button saveButton;
    private Button addAppButton;
    private Button deleteButton;
    private Button learnRemoteButton;
    private LinearLayout remoteListGroup;
    private Spinner profileSpinner;
    private LinearLayout editorGroup;
    private TextView emptyConfig;
    private AlertDialog remoteLearningDialog;
    private TextView remoteLearningProgress;
    private int remoteLearningIndex;
    private String remoteLearningDeviceName;
    private String remoteLearningProtocol;
    private String remoteLearningConsumerName;
    private boolean remoteMotionPressed;
    private final int[] remoteLearningCodes = new int[6];
    private final Runnable finishRemoteGestureLearning = () -> saveLearnedRemote();

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            mapper.refresh(snapshot -> {
                if (!visible || isDestroyed()) return;
                setTextIfChanged(detailStatus, MapperConnection.statusMessage(snapshot.status));
                String logs = snapshot.logs == null ? "" : snapshot.logs.trim();
                setTextIfChanged(logText, logs.isEmpty() ? "按下遥控器后，这里会显示最近的操作。" : lastLines(logs, 12));
                updateConnectionState();
            });
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mapper = MapperConnection.get(this);
        loadConfig();
        buildUi();
        mapper.addListener(this);
        renderProfileList(0);
        updateConnectionState();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        busy = mapper.isBusy();
        handler.post(poll);
        updateConnectionState();
    }

    @Override protected void onStop() {
        visible = false;
        handler.removeCallbacks(poll);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        updateHighlightPermission();
    }

    @Override protected void onDestroy() {
        mapper.removeListener(this);
        // The application-wide connection is deliberately kept for background operation.
        super.onDestroy();
    }

    @Override public void onConnectionChanged() {
        if (!isDestroyed()) {
            busy = mapper.isBusy();
            updateConnectionState();
        }
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout content = vertical();
        content.setPadding(dp(20), dp(22), dp(20), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        TextView title = text("蓝牙按键映射", 28, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);
        TextView subtitle = text("六个按键，按你的习惯设置", 15, MUTED);
        add(content, subtitle, 6, 20);

        LinearLayout statusCard = card(content);
        LinearLayout heading = horizontal();
        TextView statusTitle = text("运行状态", 18, INK);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(statusTitle, new LinearLayout.LayoutParams(0, -2, 1));
        runningStatus = text("未开始", 13, MUTED);
        runningStatus.setPadding(dp(10), dp(5), dp(10), dp(5));
        runningStatus.setBackground(round(Color.rgb(237, 240, 247), 12));
        heading.addView(runningStatus);
        statusCard.addView(heading);
        detailStatus = text("连接蓝牙遥控器后，点击开始使用", 15, MUTED);
        add(statusCard, detailStatus, 12, 12);
        LinearLayout controls = horizontal();
        startButton = button("开始使用", true);
        stopButton = button("停止", false);
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(50), 2);
        startParams.setMargins(0, 0, dp(10), 0);
        controls.addView(startButton, startParams);
        controls.addView(stopButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        statusCard.addView(controls);
        startButton.setOnClickListener(view -> startMapping());
        stopButton.setOnClickListener(view -> stopMapping());

        Switch highlight = new Switch(this);
        highlight.setText("高亮 YouTube 转写文稿当前行");
        highlight.setChecked(config.optBoolean("highlightTranscript"));
        add(statusCard, highlight, 12, 0);
        highlightStatus = text("", 13, MUTED);
        add(statusCard, highlightStatus, 6, 0);
        overlayPermissionButton = button("去授权", false);
        add(statusCard, overlayPermissionButton, 6, 0);
        overlayPermissionButton.setOnClickListener(view -> openOverlaySettings());
        updateHighlightPermission();
        highlight.setOnCheckedChangeListener((view, enabled) -> {
            try {
                config.put("highlightTranscript", enabled);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CONFIG_KEY, config.toString()).apply();
            } catch (JSONException error) {
                showError("无法保存文稿高亮设置");
                return;
            }
            updateHighlightPermission();
            if (mapper.isRunning()) applyMapping(true);
            if (enabled && !android.provider.Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("允许显示高亮描边")
                        .setMessage("请在系统设置中选择「遥控映射」，打开「显示在其他应用上层」，然后返回此应用。")
                        .setPositiveButton("打开系统设置", (dialog, which) -> openOverlaySettings())
                        .setNegativeButton("稍后", null)
                        .show();
            }
        });
        Switch sentenceLoop = new Switch(this);
        sentenceLoop.setText("打开文稿时按句循环");
        sentenceLoop.setChecked(config.optBoolean("loopTranscriptLine"));
        add(statusCard, sentenceLoop, 8, 0);
        sentenceLoop.setOnCheckedChangeListener((view, enabled) -> {
            try {
                config.put("loopTranscriptLine", enabled);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CONFIG_KEY, config.toString()).apply();
                if (mapper.isRunning()) applyMapping(true);
            } catch (JSONException error) {
                showError("无法保存循环设置");
            }
        });
        Button shortcutGuide = button("YouTube 遥控器按键速查", false);
        add(statusCard, shortcutGuide, 8, 0);
        shortcutGuide.setOnClickListener(view -> showShortcutGuide());

        LinearLayout permissionCard = card(content);
        LinearLayout permissionRow = horizontal();
        LinearLayout permissionTexts = vertical();
        TextView permissionTitle = text("Shizuku 连接", 17, INK);
        permissionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        permissionTexts.addView(permissionTitle);
        shizukuStatus = text("正在检查…", 14, MUTED);
        add(permissionTexts, shizukuStatus, 5, 0);
        permissionRow.addView(permissionTexts, new LinearLayout.LayoutParams(0, -2, 1));
        permissionButton = button("去启动", false);
        permissionRow.addView(permissionButton, new LinearLayout.LayoutParams(dp(96), dp(46)));
        permissionCard.addView(permissionRow);
        TextView permissionHint = text("媒体按键映射不需要无障碍权限，需要 Shizuku 授权。重启手机后，需要重新启动 Shizuku。", 13, MUTED);
        add(permissionCard, permissionHint, 12, 0);
        permissionButton.setOnClickListener(view -> {
            if (mapper.isShizukuReady() && !mapper.hasPermission()) {
                try { mapper.requestPermission(); }
                catch (Throwable error) { showError("暂时无法请求授权，请打开 Shizuku 检查后重试"); }
            } else openShizuku();
        });

        LinearLayout configCard = card(content);
        LinearLayout configHeading = horizontal();
        TextView configTitle = text("按键配置", 18, INK);
        configTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        configHeading.addView(configTitle, new LinearLayout.LayoutParams(0, -2, 1));
        addAppButton = button("添加 App", false);
        configHeading.addView(addAppButton, new LinearLayout.LayoutParams(dp(106), dp(44)));
        configCard.addView(configHeading);
        LinearLayout profileRow = horizontal();
        profileSpinner = new Spinner(this);
        profileRow.addView(profileSpinner, new LinearLayout.LayoutParams(0, dp(50), 1));
        deleteButton = button("删除配置", false);
        deleteButton.setTextColor(Color.rgb(176, 52, 63));
        profileRow.addView(deleteButton, new LinearLayout.LayoutParams(dp(104), dp(44)));
        add(configCard, profileRow, 10, 4);
        emptyConfig = text("还没有 App 配置，点击“添加 App”开始设置。", 15, MUTED);
        add(configCard, emptyConfig, 6, 6);
        configHint = text("YouTube 暂停时，左右键三击跳首尾。中键设为播放 / 暂停时，先跳转并播放一段，暂停后双击循环，三击点赞。", 13, MUTED);
        add(configCard, configHint, 2, 12);
        TextView remoteHint = text("内置与学习到的遥控器平级管理。可同时启用多台设备；每台设备使用自己的按键学习配置。", 13, MUTED);
        add(configCard, remoteHint, 8, 4);
        remoteListGroup = vertical();
        configCard.addView(remoteListGroup);
        learnRemoteButton = button("添加遥控器", false);
        add(configCard, learnRemoteButton, 4, 8);
        learnRemoteButton.setOnClickListener(view -> startRemoteLearning());
        renderRemoteList();
        editorGroup = vertical();
        configCard.addView(editorGroup);
        for (int i = 0; i < editors.length; i++) {
            editors[i] = new ButtonEditor(i);
            editorGroup.addView(editors[i].root);
            if (i < editors.length - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(Color.rgb(239, 241, 247));
                editorGroup.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
        saveButton = button("保存配置", true);
        add(configCard, saveButton, 16, 0);
        saveButton.setOnClickListener(view -> saveConfiguration());
        addAppButton.setOnClickListener(view -> showAppPicker());
        deleteButton.setOnClickListener(view -> confirmDeleteProfile());
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (rendering || position == profileIndex || position < 0 || position >= profiles.length()
                        || position != profileSpinner.getSelectedItemPosition()) return;
                if (!readEditors(true)) {
                    rendering = true;
                    profileSpinner.setSelection(profileIndex);
                    rendering = false;
                    return;
                }
                profileIndex = position;
                renderEditors();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        LinearLayout logCard = card(content);
        TextView logTitle = text("最近操作", 18, INK);
        logTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logCard.addView(logTitle);
        logText = text("按下遥控器后，这里会显示最近的操作。", 14, MUTED);
        logText.setTextIsSelectable(true);
        logText.setLineSpacing(dp(4), 1f);
        add(logCard, logText, 12, 0);
        TextView footer = text("切换到已配置的视频 App 时自动映射；其他页面恢复遥控器原来的操作。\n媒体控制效果取决于目标 App 的支持情况。", 13, MUTED);
        footer.setGravity(Gravity.CENTER);
        add(content, footer, 6, 0);
    }

    private void updateConnectionState() {
        if (shizukuStatus == null) return;
        boolean ready = mapper.isShizukuReady();
        boolean granted = mapper.hasPermission();
        boolean running = mapper.isRunning();
        boolean hasProfiles = profiles.length() > 0;
        JSONArray remotes = config.optJSONArray("remotes");
        int remoteCount = remotes == null ? 0 : remotes.length();
        String nextState = ready + ":" + granted + ":" + running + ":" + busy + ":"
                + hasProfiles + ":" + hasSelectedProfile() + ":" + remoteCount;
        if (nextState.equals(connectionUiState)) return;
        connectionUiState = nextState;
        shizukuStatus.setText(!ready ? "尚未启动" : granted ? "已连接 · 已授权" : "已启动 · 等待授权");
        shizukuStatus.setTextColor(granted ? Color.rgb(28, 128, 91) : MUTED);
        permissionButton.setText(!ready ? "去启动" : granted ? "管理" : "授权");
        runningStatus.setText(busy ? "处理中" : running ? "运行中" : "未开始");
        runningStatus.setTextColor(running ? Color.rgb(21, 118, 81) : MUTED);
        runningStatus.setBackground(round(running ? Color.rgb(227, 246, 235) : Color.rgb(237, 240, 247), 12));
        startButton.setText(busy ? "请稍候…" : running ? "正在使用" : "开始使用");
        startButton.setEnabled(!busy && !running && hasProfiles);
        startButton.setAlpha(startButton.isEnabled() ? 1f : 0.55f);
        stopButton.setEnabled(!busy && running);
        stopButton.setAlpha(stopButton.isEnabled() ? 1f : 0.5f);
        saveButton.setEnabled(!busy && hasProfiles);
        deleteButton.setEnabled(!busy && hasSelectedProfile());
        addAppButton.setEnabled(!busy);
        learnRemoteButton.setEnabled(!busy && !running);
        learnRemoteButton.setText("添加遥控器");
        renderRemoteList();
        profileSpinner.setEnabled(!busy && hasProfiles);
    }

    private JSONArray remoteArray() {
        JSONArray remotes = config.optJSONArray("remotes");
        if (remotes != null) {
            boolean changed = ensureBuiltInEntry(remotes, "builtin-ble-m3", "BLE-M3", "BLE-M3 Mouse", "BLE-M3 Consumer Control");
            changed |= ensureBuiltInEntry(remotes, "builtin-beauty-r1", "Beauty-R1", "Beauty-R1", "Beauty-R1 Consumer Control");
            if (changed) {
                try { config.put("remotes", remotes); }
                catch (JSONException ignored) { }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CONFIG_KEY, config.toString()).apply();
            }
            return remotes;
        }
        remotes = new JSONArray();
        JSONObject builtIn = config.optJSONObject("builtInRemotes");
        try {
            remotes.put(new JSONObject()
                    .put("id", "builtin-ble-m3").put("type", "builtin").put("deviceName", "BLE-M3 Mouse")
                    .put("consumerName", "BLE-M3 Consumer Control").put("protocol", "gesture")
                    .put("enabled", builtIn == null || builtIn.optBoolean("bleM3", true)));
            remotes.put(new JSONObject()
                    .put("id", "builtin-beauty-r1").put("type", "builtin").put("deviceName", "Beauty-R1")
                    .put("consumerName", "Beauty-R1 Consumer Control").put("protocol", "gesture")
                    .put("enabled", builtIn == null || builtIn.optBoolean("beautyR1", true)));
            JSONObject old = config.optJSONObject("learnedRemote");
            if (old != null) {
                JSONObject migrated = new JSONObject(old.toString());
                migrated.put("id", "learned-legacy").put("type", "learned").put("enabled", true);
                remotes.put(migrated);
            }
            config.put("remotes", remotes);
            config.remove("builtInRemotes");
            config.remove("learnedRemote");
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CONFIG_KEY, config.toString()).apply();
        } catch (JSONException ignored) { }
        return remotes;
    }

    private boolean ensureBuiltInEntry(JSONArray remotes, String id, String label, String deviceName, String consumerName) {
        for (int i = 0; i < remotes.length(); i++) {
            JSONObject item = remotes.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return false;
        }
        try {
            remotes.put(new JSONObject().put("id", id).put("type", "builtin").put("label", label)
                    .put("deviceName", deviceName).put("consumerName", consumerName)
                    .put("protocol", "gesture").put("enabled", true));
            return true;
        } catch (JSONException ignored) { return false; }
    }

    private void renderRemoteList() {
        if (remoteListGroup == null) return;
        remoteListGroup.removeAllViews();
        JSONArray remotes = remoteArray();
        for (int i = 0; i < remotes.length(); i++) {
            JSONObject remote = remotes.optJSONObject(i);
            if (remote == null) continue;
            final String id = remote.optString("id");
            final boolean learned = "learned".equals(remote.optString("type"));
            LinearLayout row = horizontal();
            CheckBox enabled = new CheckBox(this);
            enabled.setText(remote.optString("label", remote.optString("deviceName", "遥控器"))
                    + (learned ? " · 已学习" : " · 内置"));
            enabled.setTextColor(INK);
            enabled.setChecked(remote.optBoolean("enabled", true));
            enabled.setEnabled(!busy && !mapper.isRunning());
            enabled.setOnCheckedChangeListener((button, checked) -> {
                try {
                    JSONObject current = findRemote(id);
                    if (current == null) return;
                    current.put("enabled", checked);
                    persistRemoteArray();
                    if (mapper.isRunning()) applyMapping(true);
                } catch (JSONException error) { showError("遥控器设置未能保存，请重试。"); }
            });
            row.addView(enabled, new LinearLayout.LayoutParams(0, dp(48), 1));
            if (learned) {
                Button remove = button("删除", false);
                remove.setTextColor(Color.rgb(176, 52, 63));
                remove.setEnabled(!busy && !mapper.isRunning());
                remove.setOnClickListener(view -> removeRemote(id));
                row.addView(remove, new LinearLayout.LayoutParams(dp(72), dp(42)));
            }
            remoteListGroup.addView(row);
        }
    }

    private JSONObject findRemote(String id) {
        JSONArray remotes = remoteArray();
        for (int i = 0; i < remotes.length(); i++) {
            JSONObject item = remotes.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }

    private void persistRemoteArray() {
        try { config.put("remotes", remoteArray()); } catch (JSONException ignored) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CONFIG_KEY, config.toString()).apply();
        updateConnectionState();
    }

    private void removeRemote(String id) {
        JSONArray old = remoteArray();
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null || !id.equals(item.optString("id"))) next.put(item);
        }
        try { config.put("remotes", next); } catch (JSONException ignored) { }
        persistRemoteArray();
        toast("已删除学习配置");
    }

    private void startRemoteLearning() {
        if (busy || mapper.isRunning()) {
            showError("请先停止按键映射，再学习新遥控器。");
            return;
        }
        if (remoteArray().length() >= 16) {
            showError("最多保存 16 台遥控器配置；请先删除不用的条目。");
            return;
        }
        if (!persistConfig()) return;
        showRemoteLearningDialog();
    }

    private void showRemoteLearningDialog() {
        remoteLearningIndex = 0;
        remoteLearningDeviceName = null;
        remoteLearningProtocol = null;
        remoteLearningConsumerName = null;
        remoteMotionPressed = false;
        handler.removeCallbacks(finishRemoteGestureLearning);
        java.util.Arrays.fill(remoteLearningCodes, 0);
        LinearLayout body = vertical();
        body.setPadding(dp(22), dp(8), dp(22), dp(4));
        body.addView(text("请确认遥控器已通过蓝牙连接。本次支持安卓识别为键盘/媒体键、相对鼠标手势或触摸坐标的设备。", 14, MUTED));
        remoteLearningProgress = text("准备好后依次按：上、下、左、右、中、第六键。", 16, INK);
        add(body, remoteLearningProgress, 14, 4);
        remoteLearningDialog = new AlertDialog.Builder(this).setTitle("学习新遥控器")
                .setView(body).setPositiveButton("开始学习", null)
                .setNegativeButton("取消", (dialog, which) -> remoteLearningDialog = null).create();
        remoteLearningDialog.setCanceledOnTouchOutside(false);
        remoteLearningDialog.setOnShowListener(ignored -> remoteLearningDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    remoteLearningIndex = 0;
                    remoteLearningDeviceName = null;
                    remoteLearningProtocol = null;
                    remoteLearningConsumerName = null;
                    remoteMotionPressed = false;
                    java.util.Arrays.fill(remoteLearningCodes, 0);
                    remoteLearningProgress.setText("现在按上键（1/6）");
                    remoteLearningDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    remoteLearningDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                    Toast.makeText(this, "开始学习，请按上键", Toast.LENGTH_SHORT).show();
                }));
        remoteLearningDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) return false;
            if (remoteLearningIndex == 0 && remoteLearningProgress.getText().toString().startsWith("准备好"))
                return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (event.getRepeatCount() != 0) return true;
            InputDevice device = InputDevice.getDevice(event.getDeviceId());
            int scanCode = event.getScanCode();
            if (device == null || !device.isExternal()) return true;
            if (remoteLearningProtocol != null && !"keys".equals(remoteLearningProtocol)) {
                if (remoteLearningIndex >= 5 && (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                        || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                    remoteLearningConsumerName = normalizeConsumerName(device.getName());
                    remoteLearningProgress.setText("已识别第六键的媒体接口。继续按第六键完成学习（6/6）");
                }
                return true;
            }
            if (scanCode <= 0) {
                remoteLearningProgress.setText("安卓没有提供可学习的硬件键码。请改用鼠标手势/触摸型遥控器，或标准键盘键。");
                return true;
            }
            String name = device.getName();
            if (remoteLearningProtocol == null) remoteLearningProtocol = "keys";
            if (remoteLearningDeviceName == null) remoteLearningDeviceName = name;
            if (!remoteLearningDeviceName.equals(name)) {
                Toast.makeText(this, "请六次都使用同一个遥控器", Toast.LENGTH_SHORT).show();
                return true;
            }
            for (int i = 0; i < remoteLearningIndex; i++) {
                if (remoteLearningCodes[i] == scanCode) {
                    Toast.makeText(this, "这个键码已用于前面的按键，请按正确的下一键", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
            remoteLearningCodes[remoteLearningIndex] = scanCode;
            remoteLearningIndex++;
            if (remoteLearningIndex < remoteLearningCodes.length) {
                remoteLearningProgress.setText("已记录 " + BUTTON_LABELS[remoteLearningIndex - 1]
                        + "。现在按" + BUTTON_LABELS[remoteLearningIndex] + "（" + (remoteLearningIndex + 1) + "/6）");
                return true;
            }
            saveLearnedRemote();
            return true;
        });
        remoteLearningDialog.show();
        remoteLearningDialog.getWindow().setLayout(-1, -1);
        View dialogDecor = remoteLearningDialog.getWindow().getDecorView();
        dialogDecor.setOnTouchListener((view, event) -> captureRemoteGesture(event));
        dialogDecor.setOnGenericMotionListener((view, event) -> captureRemoteGesture(event));
    }

    private boolean captureRemoteGesture(MotionEvent event) {
        if (remoteLearningDialog == null || remoteLearningIndex == 0
                && remoteLearningProgress.getText().toString().startsWith("准备好")) return false;
        InputDevice device = InputDevice.getDevice(event.getDeviceId());
        if (device == null || !device.isExternal()) return false;
        int source = event.getSource();
        String protocol;
        boolean mouseSource = (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
        if ((source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                || mouseSource) protocol = "gesture";
        else return false;
        if (remoteLearningProtocol != null && !"keys".equals(remoteLearningProtocol)
                && !remoteLearningProtocol.equals(protocol)) return true;
        if (remoteLearningProtocol == null) remoteLearningProtocol = protocol;
        if ("keys".equals(remoteLearningProtocol)) return true;
        String name = normalizeGestureDeviceName(device.getName(), mouseSource);
        if (remoteLearningDeviceName == null) remoteLearningDeviceName = name;
        if (!remoteLearningDeviceName.equals(name)) {
            Toast.makeText(this, "请六次都使用同一个遥控器", Toast.LENGTH_SHORT).show();
            return true;
        }
        int action = event.getActionMasked();
        boolean pressed = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS;
        boolean released = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_BUTTON_RELEASE;
        if (pressed) remoteMotionPressed = true;
        if (released && remoteMotionPressed && remoteLearningIndex < BUTTON_LABELS.length) {
            remoteMotionPressed = false;
            remoteLearningIndex++;
            if (remoteLearningIndex < BUTTON_LABELS.length) {
                remoteLearningProgress.setText("已记录 " + BUTTON_LABELS[remoteLearningIndex - 1]
                        + "。现在按" + BUTTON_LABELS[remoteLearningIndex] + "（" + (remoteLearningIndex + 1) + "/6）");
            } else {
                remoteLearningProgress.setText("六键已记录，正在确认是否还有单独的第六键接口…");
                handler.postDelayed(finishRemoteGestureLearning, 250);
            }
        }
        return true;
    }

    private void saveLearnedRemote() {
        handler.removeCallbacks(finishRemoteGestureLearning);
        try {
            JSONObject learned = new JSONObject().put("id", "learned-" + java.util.UUID.randomUUID())
                    .put("type", "learned").put("enabled", true)
                    .put("label", remoteLearningDeviceName).put("deviceName", remoteLearningDeviceName)
                    .put("protocol", remoteLearningProtocol);
            if (remoteLearningConsumerName != null) {
                learned.put("consumerName", remoteLearningConsumerName);
            } else if ("gesture".equals(remoteLearningProtocol)) {
                learned.put("consumerName", normalizeConsumerName(remoteLearningDeviceName));
            }
            if ("keys".equals(remoteLearningProtocol)) {
                JSONObject keys = new JSONObject();
                for (int i = 0; i < BUTTON_KEYS.length; i++) keys.put(BUTTON_KEYS[i], remoteLearningCodes[i]);
                learned.put("keys", keys);
            }
            remoteArray().put(learned);
            config.put("remotes", remoteArray());
            config.remove("learnedRemote");
            if (!getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CONFIG_KEY, config.toString()).commit()) {
                showError("学习结果未能保存，请重试。");
                return;
            }
            remoteLearningProgress.setText("六个按键已学习完成：" + remoteLearningDeviceName);
            Toast.makeText(this, "遥控器学习完成", Toast.LENGTH_SHORT).show();
            remoteLearningDialog.dismiss();
            remoteLearningDialog = null;
            updateConnectionState();
        } catch (JSONException error) {
            showError("无法保存学习结果，请重试。");
        }
    }

    private String normalizeConsumerName(String androidDeviceName) {
        if (androidDeviceName == null || androidDeviceName.isEmpty())
            return remoteLearningDeviceName + " Consumer Control";
        if (androidDeviceName.endsWith(" Consumer Control")) return androidDeviceName;
        if (androidDeviceName.equals(remoteLearningDeviceName)) {
            return remoteLearningDeviceName.endsWith(" Mouse")
                    ? remoteLearningDeviceName.substring(0, remoteLearningDeviceName.length() - 6) + " Consumer Control"
                    : remoteLearningDeviceName + " Consumer Control";
        }
        return androidDeviceName + " Consumer Control";
    }

    private static String normalizeGestureDeviceName(String androidName, boolean mouseSource) {
        if (androidName != null && androidName.endsWith(" Consumer Control")) {
            String base = androidName.substring(0, androidName.length() - " Consumer Control".length());
            return mouseSource ? base + " Mouse" : base;
        }
        return androidName;
    }

    private static void setTextIfChanged(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private void updateHighlightPermission() {
        if (highlightStatus == null) return;
        boolean enabled = config.optBoolean("highlightTranscript");
        boolean authorized = android.provider.Settings.canDrawOverlays(this);
        highlightStatus.setVisibility(enabled ? View.VISIBLE : View.GONE);
        overlayPermissionButton.setVisibility(enabled && !authorized ? View.VISIBLE : View.GONE);
        highlightStatus.setText(authorized ? "已授权。启动映射后打开 YouTube 转写文稿。"
                : "等待授权。在系统设置中允许遥控映射显示在其他应用上层。");
    }

    private void showShortcutGuide() {
        String[][] shortcuts = {
                {"上／下单击", "后退／前进 5 秒"},
                {"左／右单击", "文稿打开时跳上一句／下一句，否则后退／前进 10 秒"},
                {"左／右三击", "暂停时跳到开头／结束前约 1.5 秒"},
                {"中键单击", "播放／暂停。循环中单击会退出并暂停"},
                {"中键双击", "暂停时开始循环。自由循环需先遥控跳转、播放一段再暂停"},
                {"中键三击", "点赞"},
                {"第六键单击", "打开／关闭转写文稿"}
        };
        LinearLayout rows = vertical();
        rows.setPadding(dp(22), dp(8), dp(22), dp(8));
        add(rows, text("以下为默认按键。单击动作和秒数可在配置中调整。", 13, MUTED), 0, 8);
        for (String[] shortcut : shortcuts) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.TOP);
            row.addView(text(shortcut[0], 14, INK), new LinearLayout.LayoutParams(dp(106), -2));
            row.addView(text(shortcut[1], 14, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
            add(rows, row, 8, 0);
        }
        add(rows, text("开启按句循环并打开文稿后，暂停时双击会循环当前句。", 13, MUTED), 12, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        new AlertDialog.Builder(this).setTitle("YouTube 默认按键")
                .setView(scroll).setPositiveButton("知道了", null).show();
    }

    private void openOverlaySettings() {
        String action = android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION;
        try {
            startActivity(new Intent(action, Uri.parse("package:" + getPackageName())));
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            try {
                startActivity(new Intent(action));
            } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                showError("请手动打开系统设置，搜索「显示在其他应用上层」，并为遥控映射开启。");
            }
        }
    }

    private void startMapping() {
        if (profiles.length() == 0) {
            toast("请先添加一个 App 配置");
            return;
        }
        if (!mapper.isShizukuReady()) {
            showError("请先在 Shizuku 中启动服务，然后回到这里开始使用。");
            openShizuku();
            return;
        }
        if (!mapper.hasPermission()) {
            try { mapper.requestPermission(); }
            catch (Throwable error) { showError("请打开 Shizuku，授权本应用后重试。"); }
            return;
        }
        if (!persistConfig()) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1805);
        }
        applyMapping(false);
    }

    private void applyMapping(boolean updating) {
        busy = true;
        detailStatus.setText(updating ? "正在应用新的配置…" : "正在连接遥控器…");
        updateConnectionState();
        Intent service = foregroundIntent().setAction("START").putExtra("config_json", config.toString());
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        } catch (RuntimeException error) {
            busy = false;
            showError("无法启动后台服务，请保持本应用在前台后重试。");
            updateConnectionState();
            return;
        }
    }

    private void stopMapping() {
        busy = true;
        updateConnectionState();
        try { startService(foregroundIntent().setAction("STOP")); }
        catch (RuntimeException error) {
            busy = false;
            showError("暂时无法停止，请通过通知栏中的停止按钮重试。");
            updateConnectionState();
        }
    }

    private Intent foregroundIntent() {
        return new Intent().setClassName(this, "com.guodaxia.blem3mapper.MappingForegroundService");
    }

    private void saveConfiguration() {
        if (!persistConfig()) return;
        if (mapper.isRunning()) applyMapping(true);
        else toast("配置已保存");
    }

    private boolean persistConfig() {
        if (!readEditors(true)) return false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CONFIG_KEY, config.toString()).apply();
        configHint.setText("配置已保存。切换到对应视频 App 即可使用。");
        return true;
    }

    private void loadConfig() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(CONFIG_KEY, null);
        try {
            config = new JSONObject(saved == null ? defaultConfig() : saved);
            profiles = config.getJSONArray("profiles");
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.getJSONObject(i);
                profile.getString("package");
                profile.getJSONObject("buttons");
            }
        } catch (JSONException error) {
            try {
                config = new JSONObject(defaultConfig());
                profiles = config.getJSONArray("profiles");
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            toast("已载入默认配置，请检查后保存");
        }
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean removedPreview = config.remove("timedCaptionDemo") != null;
        removedPreview |= config.remove("timedCaptionVtt") != null;
        if (removedPreview) preferences.edit().putString(CONFIG_KEY, config.toString()).apply();
        boolean updatePhoto = !preferences.getBoolean(PHOTO_TRANSCRIPT_MIGRATED, false);
        boolean updateCaptions = !preferences.getBoolean(ADAPTIVE_CAPTIONS_MIGRATED, false);
        if (updatePhoto || updateCaptions) {
            try {
                boolean changed = ProfileConfig.migrateYoutubeActions(config, updatePhoto, updateCaptions);
                SharedPreferences.Editor editor = preferences.edit()
                        .putBoolean(PHOTO_TRANSCRIPT_MIGRATED, true)
                        .putBoolean(ADAPTIVE_CAPTIONS_MIGRATED, true);
                if (changed) {
                    if (saved != null && !preferences.contains(CONFIG_BEFORE_ADAPTIVE_V2))
                        editor.putString(CONFIG_BEFORE_ADAPTIVE_V2, saved);
                    editor.putString(CONFIG_KEY, config.toString());
                }
                editor.apply();
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    private static String defaultConfig() {
        return "{\"version\":1,\"profiles\":[{\"name\":\"YouTube\",\"package\":\"com.google.android.youtube\",\"buttons\":{"
                + "\"UP\":{\"action\":\"seek\",\"seconds\":-5},\"DOWN\":{\"action\":\"seek\",\"seconds\":5},"
                + "\"LEFT\":{\"action\":\"caption_previous\",\"seconds\":-10},\"RIGHT\":{\"action\":\"caption_next\",\"seconds\":10},"
                + "\"CENTER\":{\"action\":\"toggle\"},\"PHOTO\":{\"action\":\"transcript_toggle\"}}}]}";
    }

    private void renderProfileList(int selected) {
        rendering = true;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            names.add(profile.optString("name", profile.optString("package")));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileIndex = profiles.length() == 0 ? -1 : Math.max(0, Math.min(selected, profiles.length() - 1));
        profileSpinner.setAdapter(adapter);
        if (profileIndex >= 0) profileSpinner.setSelection(profileIndex);
        rendering = false;
        renderEditors();
        updateConnectionState();
    }

    private void renderEditors() {
        rendering = true;
        boolean hasProfile = hasSelectedProfile();
        profileSpinner.setVisibility(hasProfile ? View.VISIBLE : View.GONE);
        deleteButton.setVisibility(hasProfile ? View.VISIBLE : View.GONE);
        editorGroup.setVisibility(hasProfile ? View.VISIBLE : View.GONE);
        saveButton.setVisibility(hasProfile ? View.VISIBLE : View.GONE);
        emptyConfig.setVisibility(hasProfile ? View.GONE : View.VISIBLE);
        if (!hasProfile) {
            configHint.setText("没有配置时不会接管遥控器。可随时重新添加 App。");
            rendering = false;
            return;
        }
        boolean youtube = "com.google.android.youtube".equals(profiles.optJSONObject(profileIndex).optString("package"));
        configHint.setText(youtube
                ? "文稿打开时，字幕动作切换句子。关闭时按右侧秒数跳转。暂停时左右键三击跳首尾。中键设为播放 / 暂停时，先跳转并播放一段，暂停后双击循环，三击点赞。更改后点击保存。"
                : "更改后点击保存。字幕跳转和开关转写文稿目前仅支持 YouTube。");
        JSONObject buttons = profiles.optJSONObject(profileIndex).optJSONObject("buttons");
        for (int i = 0; i < editors.length; i++) {
            JSONObject value = buttons == null ? null : buttons.optJSONObject(BUTTON_KEYS[i]);
            String action = value == null ? "none" : value.optString("action", "none");
            int defaultSeconds = "caption_previous".equals(action) ? -10 : "caption_next".equals(action) ? 10 : 5;
            int seconds = value == null ? defaultSeconds : value.optInt("seconds", defaultSeconds);
            int selection = "seek".equals(action) ? (seconds < 0 ? 0 : 1)
                    : "caption_previous".equals(action) ? 2 : "caption_next".equals(action) ? 3
                    : "toggle".equals(action) ? 4 : "transcript_toggle".equals(action) ? 5 : "like".equals(action) ? 7 : 6;
            editors[i].action.setSelection(selection);
            editors[i].seconds.setText(String.valueOf(Math.max(1, Math.abs(seconds))));
            editors[i].secondsGroup.setVisibility(selection < 4 ? View.VISIBLE : View.INVISIBLE);
        }
        rendering = false;
    }

    private boolean readEditors(boolean showErrors) {
        if (!hasSelectedProfile()) return profiles.length() == 0;
        JSONObject buttons = new JSONObject();
        try {
            for (int i = 0; i < editors.length; i++) {
                int action = editors[i].action.getSelectedItemPosition();
                JSONObject value = new JSONObject();
                if (action < 4) {
                    int seconds;
                    try { seconds = Integer.parseInt(editors[i].seconds.getText().toString().trim()); }
                    catch (NumberFormatException error) { seconds = 0; }
                    if (seconds < 1 || seconds > 300) {
                        if (showErrors) {
                            editors[i].seconds.setError("1～300 秒");
                            editors[i].seconds.requestFocus();
                            toast(BUTTON_LABELS[i] + "的跳转时间请填写 1～300 秒");
                        }
                        return false;
                    }
                    if (action >= 2 && !"com.google.android.youtube".equals(
                            profiles.optJSONObject(profileIndex).optString("package"))) {
                        if (showErrors) toast("字幕行跳转目前仅支持 YouTube");
                        return false;
                    }
                    value.put("action", action < 2 ? "seek" : action == 2 ? "caption_previous" : "caption_next");
                    value.put("seconds", action == 0 || action == 2 ? -seconds : seconds);
                } else if (action == 5) {
                    if (!"com.google.android.youtube".equals(
                            profiles.optJSONObject(profileIndex).optString("package"))) {
                        if (showErrors) toast("开关转写文稿目前仅支持 YouTube");
                        return false;
                    }
                    value.put("action", "transcript_toggle");
                } else value.put("action", action == 4 ? "toggle" : action == 7 ? "like" : "none");
                buttons.put(BUTTON_KEYS[i], value);
            }
            profiles.getJSONObject(profileIndex).put("buttons", buttons);
            return true;
        } catch (JSONException error) {
            if (showErrors) showError("无法保存配置，请重新打开应用后重试。");
            return false;
        }
    }

    private boolean hasSelectedProfile() {
        return profileIndex >= 0 && profileIndex < profiles.length();
    }

    private void confirmDeleteProfile() {
        if (busy || !hasSelectedProfile()) return;
        JSONObject selected = profiles.optJSONObject(profileIndex);
        String packageName = selected.optString("package");
        String name = selected.optString("name", packageName);
        String message = "删除“" + name + "”的按键配置？\n删除后，该 App 将恢复遥控器原来的操作。";
        if (profiles.length() == 1) message += "\n这是最后一个配置，删除后会停止映射。";
        new AlertDialog.Builder(this).setTitle("删除配置").setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> deleteProfile(packageName)).show();
    }

    private void deleteProfile(String packageName) {
        if (busy) return;
        try {
            JSONObject replacement = new JSONObject(config.toString());
            JSONArray remaining = replacement.getJSONArray("profiles");
            int deletedIndex = -1;
            for (int i = 0; i < remaining.length(); i++) {
                if (packageName.equals(remaining.getJSONObject(i).optString("package"))) {
                    deletedIndex = i;
                    break;
                }
            }
            if (deletedIndex < 0) return;
            remaining.remove(deletedIndex);
            // Commit the exact remaining profiles before updating the UI or running service.
            if (!getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(CONFIG_KEY, replacement.toString()).commit()) {
                showError("配置未能保存，请重试删除。");
                return;
            }
            boolean wasRunning = mapper.isRunning();
            config = replacement;
            profiles = remaining;
            renderProfileList(Math.min(deletedIndex, profiles.length() - 1));
            if (wasRunning) {
                if (profiles.length() == 0) stopMapping();
                else applyMapping(true);
            }
            toast("配置已删除");
        } catch (JSONException error) {
            showError("暂时无法删除配置，请重新打开应用后重试。");
        }
    }

    private void showAppPicker() {
        if (!readEditors(true)) return;
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> entries = getPackageManager().queryIntentActivities(launcher, 0);
        List<AppChoice> apps = new ArrayList<>();
        Set<String> packages = new HashSet<>();
        for (ResolveInfo entry : entries) {
            String packageName = entry.activityInfo.packageName;
            if (packageName.equals(getPackageName()) || !packages.add(packageName)) continue;
            apps.add(new AppChoice(entry.loadLabel(getPackageManager()).toString(), packageName));
        }
        Collections.sort(apps, Comparator.comparing(choice -> choice.name.toLowerCase()));
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).name;
        new AlertDialog.Builder(this).setTitle("为哪个 App 添加配置？")
                .setItems(labels, (dialog, index) -> addProfile(apps.get(index).name, apps.get(index).packageName))
                .setNeutralButton("填写包名", (dialog, which) -> manualAppDialog())
                .setNegativeButton("取消", null).show();
    }

    private void manualAppDialog() {
        LinearLayout body = vertical();
        body.setPadding(dp(24), dp(10), dp(24), 0);
        EditText name = new EditText(this);
        name.setHint("App 名称");
        name.setSingleLine(true);
        body.addView(name);
        EditText packageName = new EditText(this);
        packageName.setHint("包名，例如 com.example.player");
        packageName.setSingleLine(true);
        packageName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        body.addView(packageName);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加 App 配置").setView(body)
                .setPositiveButton("添加", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String pkg = packageName.getText().toString().trim();
            if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
                packageName.setError("请填写完整的 App 包名");
                return;
            }
            String label = name.getText().toString().trim();
            addProfile(label.isEmpty() ? pkg : label, pkg);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void addProfile(String name, String packageName) {
        for (int i = 0; i < profiles.length(); i++) {
            if (packageName.equals(profiles.optJSONObject(i).optString("package"))) {
                renderProfileList(i);
                toast("已切换到该 App 的配置");
                return;
            }
        }
        try {
            JSONObject profile = new JSONObject(defaultConfig()).getJSONArray("profiles").getJSONObject(0);
            profile.put("name", name);
            profile.put("package", packageName);
            if (!"com.google.android.youtube".equals(packageName)) {
                JSONObject buttons = profile.getJSONObject("buttons");
                buttons.put("LEFT", new JSONObject().put("action", "seek").put("seconds", -10));
                buttons.put("RIGHT", new JSONObject().put("action", "seek").put("seconds", 10));
                buttons.put("PHOTO", new JSONObject().put("action", "none"));
            }
            profiles.put(profile);
            renderProfileList(profiles.length() - 1);
            configHint.setText("已添加 App，设置好按键后点击保存。");
        } catch (JSONException error) { showError("添加失败，请重试。"); }
    }

    private void openShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        try {
            if (launch != null) startActivity(launch);
            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
        } catch (RuntimeException error) { showError("请手动打开 Shizuku，启动服务并授权本应用。"); }
    }

    private final class ButtonEditor {
        final LinearLayout root;
        final Spinner action;
        final EditText seconds;
        final LinearLayout secondsGroup;

        ButtonEditor(int index) {
            root = horizontal();
            root.setMinimumHeight(dp(64));
            TextView label = text(BUTTON_LABELS[index], 16, INK);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            root.addView(label, new LinearLayout.LayoutParams(dp(58), -2));
            action = new Spinner(MainActivity.this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this,
                    android.R.layout.simple_spinner_item, ACTION_LABELS);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            action.setAdapter(adapter);
            root.addView(action, new LinearLayout.LayoutParams(0, dp(52), 1));
            secondsGroup = horizontal();
            seconds = new EditText(MainActivity.this);
            seconds.setSingleLine(true);
            seconds.setTextSize(16);
            seconds.setTextColor(INK);
            seconds.setGravity(Gravity.CENTER);
            seconds.setPadding(dp(3), 0, dp(3), 0);
            seconds.setInputType(InputType.TYPE_CLASS_NUMBER);
            seconds.setSelectAllOnFocus(true);
            seconds.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});
            secondsGroup.addView(seconds, new LinearLayout.LayoutParams(dp(46), dp(46)));
            secondsGroup.addView(text("秒", 13, MUTED), new LinearLayout.LayoutParams(dp(17), -2));
            root.addView(secondsGroup, new LinearLayout.LayoutParams(dp(63), -2));
            action.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    secondsGroup.setVisibility(position < 4 ? View.VISIBLE : View.INVISIBLE);
                    markDirty();
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            seconds.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { markDirty(); }
                @Override public void afterTextChanged(Editable value) { }
            });
        }
    }

    private void markDirty() {
        if (!rendering && configHint != null) configHint.setText("更改后点击保存；运行中的映射会立即更新。");
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = vertical();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(Color.WHITE, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(14));
        parent.addView(card, params);
        return card;
    }

    private TextView text(String value, float size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(dp(2), 1f);
        return text;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : ACCENT);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setBackground(round(primary ? ACCENT : Color.rgb(237, 239, 255), 12));
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void add(LinearLayout parent, View view, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(top), 0, dp(bottom));
        parent.addView(view, params);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private void showError(String message) {
        detailStatus.setText(message);
        new AlertDialog.Builder(this).setTitle("暂时无法完成").setMessage(message)
                .setPositiveButton("知道了", null).show();
    }

    private static String lastLines(String text, int maxLines) {
        String[] lines = text.split("\\r?\\n");
        StringBuilder result = new StringBuilder();
        for (int i = Math.max(0, lines.length - maxLines); i < lines.length; i++) {
            if (result.length() > 0) result.append('\n');
            result.append(lines[i]);
        }
        return result.toString();
    }

    private static final class AppChoice {
        final String name;
        final String packageName;
        AppChoice(String name, String packageName) { this.name = name; this.packageName = packageName; }
    }
}
