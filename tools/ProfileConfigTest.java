package com.guodaxia.blem3mapper;

import org.json.JSONArray;
import org.json.JSONObject;

/** Host-JVM regression checks for saved configuration upgrades; no device state is changed. */
public final class ProfileConfigTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        JSONObject legacy = configuration();
        JSONObject buttons = buttons(legacy);
        buttons.put("LEFT", action("caption_previous"));
        buttons.put("RIGHT", action("caption_next"));
        ProfileConfig.Profile parsed = ProfileConfig.parse(legacy.toString()).profiles.get("com.google.android.youtube");
        check(parsed.buttons.get("LEFT").seconds == -10 && parsed.buttons.get("RIGHT").seconds == 10,
                "legacy captions default to signed ten-second fallbacks");

        buttons.getJSONObject("LEFT").put("seconds", -7);
        buttons.getJSONObject("RIGHT").put("seconds", 12);
        parsed = ProfileConfig.parse(legacy.toString()).profiles.get("com.google.android.youtube");
        check(parsed.buttons.get("LEFT").seconds == -7 && parsed.buttons.get("RIGHT").seconds == 12,
                "custom caption fallbacks survive parsing");
        for (Object invalid : new Object[]{0, 7, -301, -1.5, "-10"}) {
            buttons.getJSONObject("LEFT").put("seconds", invalid);
            expectInvalid(legacy, "reject invalid previous-caption seconds: " + invalid);
        }

        JSONObject upgrade = configuration();
        buttons = buttons(upgrade);
        buttons.getJSONObject("LEFT").put("seconds", -23);
        buttons.getJSONObject("RIGHT").put("seconds", 17);
        buttons.put("CENTER", action("like"));
        JSONObject other = new JSONObject(upgrade.getJSONArray("profiles").getJSONObject(0).toString());
        other.put("package", "com.example.other");
        other.put("name", "Other app");
        String otherBefore = other.toString();
        upgrade.getJSONArray("profiles").put(other);
        check(ProfileConfig.migrateYoutubeActions(upgrade, true, true), "upgrade reports changes");
        check("caption_previous".equals(buttons.getJSONObject("LEFT").getString("action"))
                        && buttons.getJSONObject("LEFT").getInt("seconds") == -23
                        && "caption_next".equals(buttons.getJSONObject("RIGHT").getString("action"))
                        && buttons.getJSONObject("RIGHT").getInt("seconds") == 17,
                "directional seek upgrade retains separately customized seconds");
        check(buttons.getJSONObject("UP").getInt("seconds") == -3
                        && buttons.getJSONObject("DOWN").getInt("seconds") == 3
                        && "like".equals(buttons.getJSONObject("CENTER").getString("action")),
                "other customized YouTube keys are preserved");
        check("transcript_toggle".equals(buttons.getJSONObject("PHOTO").getString("action")),
                "old YouTube sixth-key like upgrades once");
        check(otherBefore.equals(other.toString()), "every non-YouTube mapping is unchanged");
        check(!ProfileConfig.migrateYoutubeActions(upgrade, true, true), "upgrade is idempotent");
        ProfileConfig.parse(upgrade.toString());

        JSONObject customized = configuration();
        buttons = buttons(customized);
        buttons.put("LEFT", action("toggle"));
        buttons.put("RIGHT", action("like"));
        check(!ProfileConfig.migrateYoutubeActions(customized, false, true),
                "custom left/right actions and already-migrated photo like are preserved");
        buttons.put("PHOTO", action("none"));
        check(!ProfileConfig.migrateYoutubeActions(customized, true, true), "disabled photo stays disabled");

        JSONObject priorTestVersion = configuration();
        buttons = buttons(priorTestVersion);
        buttons.put("LEFT", action("caption_previous"));
        buttons.put("RIGHT", action("caption_next"));
        check(ProfileConfig.migrateYoutubeActions(priorTestVersion, false, true),
                "existing test-version captions gain persisted fallback seconds");
        check(buttons.getJSONObject("LEFT").getInt("seconds") == -10
                        && buttons.getJSONObject("RIGHT").getInt("seconds") == 10,
                "test-version fallback defaults have the proper direction");
        System.out.println("Passed " + checks + " configuration checks");
    }

    private static JSONObject action(String kind) throws Exception {
        return new JSONObject().put("action", kind);
    }

    private static JSONObject configuration() throws Exception {
        JSONObject buttons = new JSONObject()
                .put("UP", action("seek").put("seconds", -3))
                .put("DOWN", action("seek").put("seconds", 3))
                .put("LEFT", action("seek").put("seconds", -10))
                .put("RIGHT", action("seek").put("seconds", 10))
                .put("CENTER", action("toggle"))
                .put("PHOTO", action("like"));
        return new JSONObject().put("version", 1).put("profiles", new JSONArray().put(new JSONObject()
                .put("name", "YouTube").put("package", "com.google.android.youtube").put("buttons", buttons)));
    }

    private static JSONObject buttons(JSONObject config) throws Exception {
        return config.getJSONArray("profiles").getJSONObject(0).getJSONObject("buttons");
    }

    private static void expectInvalid(JSONObject config, String description) throws Exception {
        try {
            ProfileConfig.parse(config.toString());
        } catch (IllegalArgumentException expected) {
            check(true, description);
            return;
        }
        throw new AssertionError(description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
        System.out.println("PASS " + description);
    }
}
