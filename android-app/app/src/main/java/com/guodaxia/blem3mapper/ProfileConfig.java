package com.guodaxia.blem3mapper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, validated configuration received through the app's private binder. */
final class ProfileConfig {
    static final String[] BUTTONS = {"UP", "DOWN", "LEFT", "RIGHT", "CENTER", "PHOTO"};
    static final String[] LABELS = {"上键", "下键", "左键", "右键", "中键", "第六键"};

    static final class Action {
        final String kind;
        final int seconds;

        Action(String kind, int seconds) {
            this.kind = kind;
            this.seconds = seconds;
        }
    }

    static final class Profile {
        final String name;
        final String packageName;
        final Map<String, Action> buttons;

        Profile(String name, String packageName, Map<String, Action> buttons) {
            this.name = name;
            this.packageName = packageName;
            this.buttons = Collections.unmodifiableMap(buttons);
        }
    }

    final Map<String, Profile> profiles;

    private ProfileConfig(Map<String, Profile> profiles) {
        this.profiles = Collections.unmodifiableMap(profiles);
    }

    static ProfileConfig parse(String json) throws Exception {
        if (json == null || json.length() > 65536)
            throw new IllegalArgumentException("方案内容过大或为空");
        JSONObject root = new JSONObject(json);
        if (integer(root, "version") != 1)
            throw new IllegalArgumentException("暂不支持此方案版本");
        JSONArray array = root.getJSONArray("profiles");
        if (array.length() > 64)
            throw new IllegalArgumentException("最多可设置 64 个应用方案");
        Map<String, Profile> profiles = new LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            String name = object.getString("name").trim();
            String packageName = object.getString("package");
            if (name.isEmpty() || name.length() > 80)
                throw new IllegalArgumentException("方案名称应为 1 至 80 个字");
            if (packageName.length() > 255 ||
                    !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
                throw new IllegalArgumentException("应用包名无效：" + name);
            if (profiles.containsKey(packageName))
                throw new IllegalArgumentException("同一应用只能有一个方案：" + name);
            JSONObject mappings = object.getJSONObject("buttons");
            if (mappings.length() != BUTTONS.length)
                throw new IllegalArgumentException("每个方案都需要完整的六键设置：" + name);
            Map<String, Action> actions = new LinkedHashMap<>();
            for (String button : BUTTONS) {
                JSONObject mapping = mappings.getJSONObject(button);
                String kind = mapping.getString("action");
                int seconds = 0;
                if ("seek".equals(kind)) {
                    seconds = integer(mapping, "seconds");
                    if (seconds == 0 || seconds < -300 || seconds > 300)
                        throw new IllegalArgumentException("跳转时间应为前进或后退 1 至 300 秒");
                } else if ("caption_previous".equals(kind) || "caption_next".equals(kind)) {
                    if (!"com.google.android.youtube".equals(packageName))
                        throw new IllegalArgumentException("字幕行跳转目前仅支持 YouTube");
                    boolean previous = "caption_previous".equals(kind);
                    seconds = mapping.has("seconds") ? integer(mapping, "seconds") : (previous ? -10 : 10);
                    if (seconds == 0 || seconds < -300 || seconds > 300 || (seconds < 0) != previous)
                        throw new IllegalArgumentException("字幕关闭时的跳转时间应为同方向的 1 至 300 秒");
                } else if ("transcript_toggle".equals(kind)) {
                    if (!"com.google.android.youtube".equals(packageName))
                        throw new IllegalArgumentException("开关转写文稿目前仅支持 YouTube");
                } else if (!"toggle".equals(kind) && !"like".equals(kind) && !"none".equals(kind)) {
                    throw new IllegalArgumentException("暂不支持的按键操作：" + kind);
                }
                actions.put(button, new Action(kind, seconds));
            }
            profiles.put(packageName, new Profile(name, packageName, actions));
        }
        return new ProfileConfig(profiles);
    }

    /** Upgrade only the YouTube defaults covered by this feature; retain custom seconds and other apps. */
    static boolean migrateYoutubeActions(JSONObject root, boolean updatePhoto, boolean updateCaptions)
            throws JSONException {
        boolean changed = false;
        JSONArray profiles = root.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            if (!"com.google.android.youtube".equals(profile.optString("package"))) continue;
            JSONObject buttons = profile.getJSONObject("buttons");
            JSONObject photo = buttons.optJSONObject("PHOTO");
            if (updatePhoto && photo != null && "like".equals(photo.optString("action"))) {
                photo.put("action", "transcript_toggle");
                changed = true;
            }
            if (updateCaptions) {
                changed |= migrateCaptionAction(buttons, "LEFT", "caption_previous", -10);
                changed |= migrateCaptionAction(buttons, "RIGHT", "caption_next", 10);
            }
        }
        return changed;
    }

    private static boolean migrateCaptionAction(JSONObject buttons, String button, String kind, int defaultSeconds)
            throws JSONException {
        JSONObject mapping = buttons.optJSONObject(button);
        if (mapping == null) return false;
        String action = mapping.optString("action");
        int seconds = mapping.optInt("seconds", 0);
        if ("seek".equals(action) && seconds != 0 && seconds >= -300 && seconds <= 300
                && (seconds < 0) == (defaultSeconds < 0)) {
            mapping.put("action", kind);
            return true;
        }
        if (kind.equals(action) && !mapping.has("seconds")) {
            mapping.put("seconds", defaultSeconds);
            return true;
        }
        return false;
    }

    private static int integer(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " 必须是整数");
        Number number = (Number) value;
        if (!Double.isFinite(number.doubleValue()) || number.doubleValue() != number.intValue())
            throw new IllegalArgumentException(key + " 必须是整数");
        return number.intValue();
    }

    static String label(String button) {
        for (int i = 0; i < BUTTONS.length; i++) if (BUTTONS[i].equals(button)) return LABELS[i];
        return button;
    }
}
