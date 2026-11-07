package com.guodaxia.blem3mapper;

import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.PlaybackState;

import org.json.JSONObject;

import java.lang.reflect.Method;

/** Device CLI using the production MediaControl, run with shell identity through app_process. */
public final class MediaControlCheck {
    private static JSONObject status(MediaControl media, String packageName) throws Exception {
        // Reuse the production session selection without adding a diagnostic API to the app.
        Method select = MediaControl.class.getDeclaredMethod("controller", String.class);
        select.setAccessible(true);
        MediaController controller = (MediaController) select.invoke(media, packageName);
        PlaybackState state = controller.getPlaybackState();
        MediaMetadata metadata = controller.getMetadata();
        JSONObject result = new JSONObject().put("package", packageName);
        if (state == null) {
            result.put("state", JSONObject.NULL);
        } else {
            result.put("state", state.getState())
                    .put("position", state.getPosition())
                    .put("actions", state.getActions())
                    .put("speed", state.getPlaybackSpeed())
                    .put("updatedElapsedMs", state.getLastPositionUpdateTime());
        }
        result.put("duration", metadata == null ? JSONObject.NULL
                : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        result.put("ratingType", controller.getRatingType());
        Rating rating = metadata == null ? null : metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
        result.put("userRating", rating == null ? JSONObject.NULL : new JSONObject()
                .put("style", rating.getRatingStyle()).put("rated", rating.isRated())
                .put("thumbUp", rating.isThumbUp()));
        result.put("hasMediaId", metadata != null && metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) != null);
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3
                || !("status".equals(args[1]) || "seek".equals(args[1]) || "toggle".equals(args[1]))
                || ("seek".equals(args[1]) ? args.length != 3 : args.length != 2)) {
            System.err.println("Usage: MediaControlCheck <package> status | seek <signed-seconds> | toggle");
            System.exit(2);
            return;
        }
        String packageName = args[0];
        String command = args[1];
        int seconds = "seek".equals(command) ? Integer.parseInt(args[2]) : 0;
        if ("seek".equals(command) && (seconds == 0 || seconds < -300 || seconds > 300))
            throw new IllegalArgumentException("Seek must be -300..-1 or 1..300 seconds");

        try {
            MediaControl media = new MediaControl();
            if ("status".equals(command)) {
                System.out.println(status(media, packageName));
            } else {
                System.out.println(media.perform(packageName, new ProfileConfig.Action(command, seconds)));
            }
        } catch (Exception error) {
            error.printStackTrace();
            System.exit(1);
            return;
        }
        // app_process creates framework binder threads; do not leave a diagnostic process running.
        System.exit(0);
    }
}
