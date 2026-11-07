import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONObject;

/** Temporary adb-shell media helper. Controls only the explicitly selected package. */
public final class YouTubeControl {
    private String targetPackage = "com.google.android.youtube";
    private final MediaSessionManager manager;

    private YouTubeControl() throws Exception {
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getContext = activityThread.getDeclaredMethod("getSystemContext");
        getContext.setAccessible(true);
        Context context = (Context) getContext.invoke(thread);
        // app_process has not gone through Zygote's media-module initialization.
        Class<?> initializer = Class.forName("android.media.MediaFrameworkPlatformInitializer");
        if (initializer.getDeclaredMethod("getMediaServiceManager").invoke(null) == null) {
            Class<?> serviceManager = Class.forName("android.media.MediaServiceManager");
            initializer.getDeclaredMethod("setMediaServiceManager", serviceManager)
                .invoke(null, serviceManager.getDeclaredConstructor().newInstance());
        }
        manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    private MediaController controller() {
        List<MediaController> controllers = manager.getActiveSessions(null);
        MediaController selected = null;
        long latest = -1;
        for (MediaController candidate : controllers) {
            if (!targetPackage.equals(candidate.getPackageName())) continue;
            PlaybackState state = candidate.getPlaybackState();
            if (state != null && state.getLastPositionUpdateTime() >= latest) {
                latest = state.getLastPositionUpdateTime();
                selected = candidate;
            }
        }
        if (selected == null) throw new IllegalStateException("No playback session for " + targetPackage);
        return selected;
    }

    private static long duration(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        return metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    private static long position(PlaybackState state) {
        long value = state.getPosition();
        if (value < 0) throw new IllegalStateException("Unknown playback position");
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            value += (long)((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        return Math.max(0, value);
    }

    private JSONObject run(String command) throws Exception {
        String[] words = command.trim().split("\\s+");
        if (words.length == 2 && words[0].equals("target")) {
            if (!words[1].matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
                throw new IllegalArgumentException("Invalid target package");
            targetPackage = words[1];
            return new JSONObject().put("ok", true).put("package", targetPackage).put("command", command);
        }
        MediaController controller = controller();
        PlaybackState state = controller.getPlaybackState();
        if (state == null) throw new IllegalStateException("No playback state");
        long current = position(state);
        long duration = duration(controller);
        JSONObject result = new JSONObject();
        result.put("ok", true).put("command", command).put("package", targetPackage)
            .put("state", state.getState()).put("position", current)
            .put("duration", duration).put("actions", state.getActions());
        if (words.length == 1 && words[0].equals("status")) return result;
        if (state.getState() != PlaybackState.STATE_PAUSED && state.getState() != PlaybackState.STATE_PLAYING) {
            throw new IllegalStateException("Player must be paused or playing; state=" + state.getState());
        }
        if (words.length == 2 && words[0].equals("seek")) {
            long delta = Long.parseLong(words[1]);
            if (delta == 0 || delta < -300000 || delta > 300000)
                throw new IllegalArgumentException("Seek delta must be nonzero and within +/-300000 ms");
            if ((state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) {
                throw new IllegalStateException("Selected player does not advertise seek support");
            }
            long target = Math.max(0, current + delta);
            if (duration > 0) target = Math.min(duration, target);
            controller.getTransportControls().seekTo(target);
            result.put("target", target);
            return result;
        }
        if (words.length == 1 && words[0].equals("toggle")) {
            if (state.getState() == PlaybackState.STATE_PLAYING) {
                if ((state.getActions() & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) == 0)
                    throw new IllegalStateException("Pause is unavailable");
                controller.getTransportControls().pause();
                result.put("requestedState", "paused");
            } else {
                if ((state.getActions() & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) == 0)
                    throw new IllegalStateException("Play is unavailable");
                controller.getTransportControls().play();
                result.put("requestedState", "playing");
            }
            return result;
        }
        throw new IllegalArgumentException("Expected target PACKAGE, status, seek DELTA_MS, or toggle");
    }

    private void respond(String line) {
        try { System.out.println(run(line)); }
        catch (Exception error) {
            try { System.out.println(new JSONObject().put("ok", false).put("error", error.getClass().getSimpleName() + ": " + error.getMessage())); }
            catch (Exception ignored) { System.out.println("{\"ok\":false,\"error\":\"Unreported error\"}"); }
        }
        System.out.flush();
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            YouTubeControl control = new YouTubeControl();
            if (args.length == 1 && args[0].equals("--stdio")) {
                System.out.println("{\"ready\":true}");
                System.out.flush();
                BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.equals("quit")) break;
                    if (!line.trim().isEmpty()) control.respond(line);
                }
            } else {
                control.respond(args.length == 0 ? "status" : String.join(" ", args));
            }
        } catch (Throwable error) {
            System.err.println("HELPER_ERROR " + error.getClass().getSimpleName() + ": " + error.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
