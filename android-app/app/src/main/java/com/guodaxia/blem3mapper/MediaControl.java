package com.guodaxia.blem3mapper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

/** Runs only inside the Shizuku user service, with the verified shell media identity. */
final class MediaControl {
    private final MediaSessionManager manager;

    // Shizuku UserService is an app_process shell process, where hidden API restrictions do not apply.
    @SuppressLint({"BlockedPrivateApi", "PrivateApi"})
    MediaControl() throws Exception {
        Context systemContext = systemContext();
        // app_process may not have run Zygote's media module initialization.
        try {
            Class<?> initializer = Class.forName("android.media.MediaFrameworkPlatformInitializer");
            if (initializer.getDeclaredMethod("getMediaServiceManager").invoke(null) == null) {
                Class<?> serviceManager = Class.forName("android.media.MediaServiceManager");
                initializer.getDeclaredMethod("setMediaServiceManager", serviceManager)
                        .invoke(null, serviceManager.getDeclaredConstructor().newInstance());
            }
        } catch (ClassNotFoundException ignored) {
            // Earlier Android versions did not have this modular initializer.
        }
        manager = (MediaSessionManager) systemContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null) throw new IllegalStateException("手机的媒体控制服务不可用");
    }

    // Never called in the normal app process: its media permissions and hidden API policy differ.
    @SuppressLint({"BlockedPrivateApi", "PrivateApi"})
    static Context systemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method current = activityThread.getDeclaredMethod("currentActivityThread");
        current.setAccessible(true);
        Object thread = current.invoke(null);
        if (thread == null) {
            Method systemMain = activityThread.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            thread = systemMain.invoke(null);
        }
        Method getContext = activityThread.getDeclaredMethod("getSystemContext");
        getContext.setAccessible(true);
        return (Context) getContext.invoke(thread);
    }

    private MediaController controller(String packageName) {
        List<MediaController> controllers = manager.getActiveSessions(null);
        MediaController selected = null;
        long latest = -1;
        for (MediaController candidate : controllers) {
            if (!packageName.equals(candidate.getPackageName())) continue;
            PlaybackState state = candidate.getPlaybackState();
            if (state != null && state.getLastPositionUpdateTime() >= latest) {
                latest = state.getLastPositionUpdateTime();
                selected = candidate;
            }
        }
        if (selected == null) throw new IllegalStateException("未找到此应用的播放会话，请先播放一下视频");
        return selected;
    }

    private static long duration(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        return metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    private static long position(PlaybackState state) {
        long position = state.getPosition();
        if (position < 0) throw new IllegalStateException("播放器没有提供播放位置");
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        return Math.max(0, position);
    }

    JSONObject perform(String packageName, ProfileConfig.Action action) throws Exception {
        if ("like".equals(action.kind) && !"com.google.android.youtube".equals(packageName))
            throw new IllegalStateException("点赞目前仅支持 YouTube，此应用尚未适配");
        MediaController controller = controller(packageName);
        PlaybackState state = controller.getPlaybackState();
        if (state == null) throw new IllegalStateException("播放器没有提供播放状态");
        if (state.getState() != PlaybackState.STATE_PAUSED && state.getState() != PlaybackState.STATE_PLAYING
                && state.getState() != PlaybackState.STATE_STOPPED)
            throw new IllegalStateException("播放器当前状态暂不支持此操作（状态码 " + state.getState() + "）");
        JSONObject result = new JSONObject().put("package", packageName)
                .put("action", action.kind).put("stateBefore", state.getState());
        if ("like".equals(action.kind)) {
            return like(controller, state, result);
        } else if ("seek".equals(action.kind)) {
            if ((state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0)
                throw new IllegalStateException("此应用的播放器不支持精确跳转");
            long current = position(state);
            long target = Math.max(0, current + action.seconds * 1000L);
            long duration = duration(controller);
            if (duration > 0) target = Math.min(duration, target);
            controller.getTransportControls().seekTo(target);
            result.put("seconds", action.seconds).put("positionBefore", current).put("target", target);
        } else if ("toggle".equals(action.kind)) {
            if (state.getState() == PlaybackState.STATE_PLAYING) {
                if ((state.getActions() & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) == 0)
                    throw new IllegalStateException("此应用的播放器不支持暂停");
                controller.getTransportControls().pause();
                result.put("requestedState", PlaybackState.STATE_PAUSED);
            } else {
                // A stopped session must explicitly support starting playback; a toggle flag alone
                // does not establish that this command is available in its current state.
                long playActions = state.getState() == PlaybackState.STATE_STOPPED
                        ? PlaybackState.ACTION_PLAY : PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE;
                if ((state.getActions() & playActions) == 0)
                    throw new IllegalStateException(state.getState() == PlaybackState.STATE_STOPPED
                            ? "播放器已停止，且当前未提供播放操作" : "此应用的播放器不支持继续播放");
                controller.getTransportControls().play();
                result.put("requestedState", PlaybackState.STATE_PLAYING);
            }
        } else {
            throw new IllegalArgumentException("无效的媒体操作");
        }
        // This is a single observed sample, not a claim that every player acknowledges immediately.
        Thread.sleep(100);
        PlaybackState observed = controller.getPlaybackState();
        if (observed != null) {
            result.put("observedState", observed.getState());
            if (observed.getPosition() >= 0) result.put("observedPosition", position(observed));
        }
        return result;
    }

    private static boolean isThumbUp(MediaMetadata metadata) {
        Rating rating = metadata == null ? null : metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
        return rating != null && rating.getRatingStyle() == Rating.RATING_THUMB_UP_DOWN
                && rating.isRated() && rating.isThumbUp();
    }

    private static JSONObject like(MediaController controller, PlaybackState state, JSONObject result)
            throws Exception {
        if (controller.getRatingType() != Rating.RATING_THUMB_UP_DOWN)
            throw new IllegalStateException("当前视频没有提供点赞功能");
        MediaMetadata metadata = controller.getMetadata();
        if (isThumbUp(metadata))
            return result.put("alreadyLiked", true).put("confirmed", true);
        if ((state.getActions() & PlaybackState.ACTION_SET_RATING) == 0)
            throw new IllegalStateException("当前视频暂不支持点赞");

        String mediaId = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        // This sets a positive rating, never toggles it. Holding PHOTO or pressing it again
        // cannot remove a like, even if the player's metadata update is delayed.
        controller.getTransportControls().setRating(Rating.newThumbRating(true));
        result.put("alreadyLiked", false).put("requestedThumbUp", true).put("confirmed", false);
        // Some players acknowledge ratings asynchronously, others do not expose USER_RATING.
        // Do not label a successfully queued request as a confirmed like without evidence.
        for (int sample = 0; sample < 6; sample++) {
            Thread.sleep(100);
            MediaMetadata observed = controller.getMetadata();
            String observedId = observed == null ? null : observed.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (mediaId != null && !mediaId.equals(observedId)) return result;
            if (mediaId != null && isThumbUp(observed)) return result.put("confirmed", true);
        }
        return result;
    }
}
