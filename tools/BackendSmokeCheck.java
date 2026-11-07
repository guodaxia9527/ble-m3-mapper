package com.guodaxia.blem3mapper;

import android.media.session.MediaSessionManager;
import android.os.SystemClock;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Read-only device smoke check: no input grabbing and no player changes. Run using app_process. */
public final class BackendSmokeCheck {
    private static int checks;

    private static void reject(JSONObject config, String description) throws Exception {
        try {
            ProfileConfig.parse(config.toString());
            throw new AssertionError("Accepted invalid configuration: " + description);
        } catch (IllegalArgumentException | org.json.JSONException expected) { checks++; }
    }

    private static JSONObject copy(String json) throws Exception { return new JSONObject(json); }
    private static JSONObject up(JSONObject config) throws Exception {
        return config.getJSONArray("profiles").getJSONObject(0).getJSONObject("buttons").getJSONObject("UP");
    }
    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    public static void main(String[] args) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        ProfileConfig valid = ProfileConfig.parse(json);
        if (valid.profiles.get("com.google.android.youtube").buttons.get("PHOTO").kind.equals("none")) checks++;
        else throw new AssertionError("Default sixth button must not mutate playback or volume");
        JSONObject invalid = copy(json); invalid.put("version", 2); reject(invalid, "version");
        for (Object value : new Object[] {0, 301, -301, 1.5, "5"}) {
            invalid = copy(json); up(invalid).put("seconds", value); reject(invalid, "seek " + value);
        }
        invalid = copy(json); up(invalid).put("action", "shell"); reject(invalid, "unsupported action");
        invalid = copy(json); invalid.getJSONArray("profiles").put(invalid.getJSONArray("profiles").getJSONObject(0));
        reject(invalid, "duplicate package");
        invalid = copy(json); invalid.getJSONArray("profiles").getJSONObject(0).getJSONObject("buttons").remove("PHOTO");
        reject(invalid, "missing sixth button");
        invalid = copy(json); invalid.getJSONArray("profiles").getJSONObject(0).put("package", "com.app;id");
        reject(invalid, "invalid package");

        JSONObject withLike = copy(json);
        withLike.getJSONArray("profiles").getJSONObject(0).getJSONObject("buttons")
                .put("PHOTO", new JSONObject().put("action", "like"));
        if (!"like".equals(ProfileConfig.parse(withLike.toString()).profiles
                .get("com.google.android.youtube").buttons.get("PHOTO").kind))
            throw new AssertionError("Like mapping was not preserved");
        checks++;
        if (!ProfileConfig.parse("{\"version\":1,\"profiles\":[]}").profiles.isEmpty())
            throw new AssertionError("Deleting the final profile must allow an empty saved list");
        checks++;

        MediaControl media = new MediaControl();
        Field managerField = MediaControl.class.getDeclaredField("manager"); managerField.setAccessible(true);
        ((MediaSessionManager) managerField.get(media)).getActiveSessions(null);
        checks++;
        try {
            media.perform("com.example.unsupported", new ProfileConfig.Action("like", 0));
            throw new AssertionError("Like must be restricted to the supported YouTube package");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("仅支持 YouTube")) throw expected;
            checks++;
        }

        Class<?> focusClass = Class.forName("com.guodaxia.blem3mapper.MapperUserService$Focus");
        Constructor<?> constructor = focusClass.getDeclaredConstructor(); constructor.setAccessible(true);
        Object focus = constructor.newInstance();
        Method scan = MapperUserService.class.getDeclaredMethod("scanFocus", Process.class, focusClass);
        scan.setAccessible(true);
        long start = SystemClock.elapsedRealtime();
        Process process = new ProcessBuilder("/system/bin/dumpsys", "-t", "1", "window").start();
        scan.invoke(null, process, focus);
        if (process.waitFor() != 0 || !field(focus, "lockKnown").equals(true) ||
                !field(focus, "awakeKnown").equals(true)) throw new AssertionError("Unable to parse real window state");
        checks++;
        System.out.println("BACKEND_SMOKE_OK checks=" + checks + " focus=" + field(focus, "packageName") +
                " awake=" + field(focus, "awake") + " locked=" + field(focus, "locked") +
                " focusReadMs=" + (SystemClock.elapsedRealtime() - start));
        System.exit(0);
    }
}
