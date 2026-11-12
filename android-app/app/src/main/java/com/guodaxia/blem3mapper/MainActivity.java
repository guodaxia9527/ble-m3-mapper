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
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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
    private TextView configHint;
    private TextView logText;
    private Button permissionButton;
    private Button startButton;
    private Button stopButton;
    private Button saveButton;
    private Button addAppButton;
    private Button deleteButton;
    private Spinner profileSpinner;
    private LinearLayout editorGroup;
    private TextView emptyConfig;

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
        detailStatus = text("连接 BLE-M3 后，点击开始使用", 15, MUTED);
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
        configHint = text("字幕动作在文稿打开时切换句子，关闭时按所设秒数跳转。YouTube 中键设为播放 / 暂停时，双击可点赞。", 13, MUTED);
        add(configCard, configHint, 2, 12);
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
        String nextState = ready + ":" + granted + ":" + running + ":" + busy + ":"
                + hasProfiles + ":" + hasSelectedProfile();
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
        profileSpinner.setEnabled(!busy && hasProfiles);
    }

    private static void setTextIfChanged(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
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
                ? "字幕动作：文稿打开时切换句子，关闭时按右侧秒数跳转。中键设为播放 / 暂停时，双击可点赞。更改后点击保存。"
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
