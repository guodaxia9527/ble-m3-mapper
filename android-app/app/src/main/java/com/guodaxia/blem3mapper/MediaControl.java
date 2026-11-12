package com.guodaxia.blem3mapper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs only inside the Shizuku user service, with the verified shell media identity. */
final class MediaControl {
    private static final String YOUTUBE = "com.google.android.youtube";
    private static final Pattern TRANSCRIPT_TIME = Pattern.compile(
            "^(?:(\\d+)小时)?\\s*(?:(\\d+)分钟)?\\s*(?:(\\d+)秒钟)");
    private final MediaSessionManager manager;
    private HandlerThread automationLooper;
    private Object automation;
    private Method getAutomationRoot;
    private Method getAutomationWindows;
    private Method disconnectAutomation;

    private static final class TranscriptRow {
        final long timeMs;
        final Rect bounds;
        TranscriptRow(long timeMs, Rect bounds) {
            this.timeMs = timeMs;
            this.bounds = new Rect(bounds);
        }
        long area() { return (long) bounds.width() * bounds.height(); }
        int centerX() { return bounds.centerX(); }
        int centerY() { return bounds.centerY(); }
    }

    private static final class TapOutcome {
        final long observedPosition;
        final boolean confirmed;
        TapOutcome(long observedPosition, boolean confirmed) {
            this.observedPosition = observedPosition;
            this.confirmed = confirmed;
        }
    }

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

    /** Opens one shell-owned accessibility automation session for the whole mapping run. */
    synchronized void prepareTranscriptAutomation() throws Exception {
        ensureTranscriptAutomation();
    }

    synchronized boolean isTranscriptAutomationReady() { return automation != null; }

    private synchronized void ensureTranscriptAutomation() throws Exception {
        if (automation != null) return;
        HandlerThread thread = new HandlerThread("ble-mapper-ui-automation");
        thread.start();
        try {
            Class<?> connectionInterface = Class.forName("android.app.IUiAutomationConnection");
            Class<?> connectionClass = Class.forName("android.app.UiAutomationConnection");
            Object connection = connectionClass.getDeclaredConstructor().newInstance();
            Class<?> uiAutomationClass = Class.forName("android.app.UiAutomation");
            Constructor<?> constructor = uiAutomationClass.getDeclaredConstructor(Looper.class, connectionInterface);
            constructor.setAccessible(true);
            Object instance = constructor.newInstance(thread.getLooper(), connection);
            Method connect = uiAutomationClass.getDeclaredMethod("connect", int.class);
            connect.setAccessible(true);
            // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES: keep any user-enabled services running.
            connect.invoke(instance, 1);
            automationLooper = thread;
            automation = instance;
            getAutomationRoot = uiAutomationClass.getMethod("getRootInActiveWindow");
            getAutomationWindows = uiAutomationClass.getMethod("getWindows");
            disconnectAutomation = uiAutomationClass.getMethod("disconnect");
        } catch (Throwable error) {
            thread.quitSafely();
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                    && error.getCause() != null ? error.getCause() : error;
            throw new IllegalStateException("无法建立快速界面读取会话：" +
                    (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
        }
    }

    synchronized void close() {
        if (automation != null && disconnectAutomation != null) {
            try { disconnectAutomation.invoke(automation); } catch (Throwable ignored) { }
        }
        automation = null;
        getAutomationRoot = null;
        getAutomationWindows = null;
        disconnectAutomation = null;
        if (automationLooper != null) {
            automationLooper.quitSafely();
            automationLooper = null;
        }
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
        } else if ("caption_previous".equals(action.kind) || "caption_next".equals(action.kind)) {
            if (!YOUTUBE.equals(packageName))
                throw new IllegalStateException("字幕行跳转目前仅支持 YouTube");
            return navigateTranscript(controller, state, action.kind, result);
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

    private JSONObject navigateTranscript(MediaController controller, PlaybackState initial,
                                         String action, JSONObject result) throws Exception {
        long actionStarted = SystemClock.elapsedRealtime();
        ensureTranscriptAutomation();
        dismissTranscriptSearchKeyboard();
        boolean next = "caption_next".equals(action);
        long current = position(initial);
        int screenHeight = systemContext().getResources().getDisplayMetrics().heightPixels;
        TranscriptRow target = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            List<TranscriptRow> rows = readTranscriptRows(screenHeight);
            target = adjacentRow(rows, current, next);
            if (target != null) break;
            if (rows.isEmpty())
                throw new IllegalStateException("未找到字幕行；请先在 YouTube 打开“转写文稿”");
            scrollTranscript(rows, next, screenHeight);
        }
        if (target == null)
            throw new IllegalStateException(next ? "转写列表中没有后续字幕行" : "转写列表中没有更早的字幕行");

        // YouTube can auto-scroll the transcript while the video is playing. Refresh both
        // the playback position and row bounds immediately before tapping to avoid stale coords.
        PlaybackState freshState = controller.getPlaybackState();
        if (freshState != null) current = position(freshState);
        List<TranscriptRow> freshRows = readTranscriptRows(screenHeight);
        TranscriptRow freshTarget = adjacentRow(freshRows, current, next);
        if (freshTarget == null) freshTarget = findRow(freshRows, target.timeMs);
        if (freshTarget == null)
            throw new IllegalStateException("转写列表正在滚动，未能重新定位目标字幕；请再按一次");
        target = freshTarget;

        long beforeTapStarted = SystemClock.elapsedRealtime();
        TapOutcome outcome = tapAndConfirm(controller, target, current);
        boolean searchFocused = !outcome.confirmed && isTranscriptSearchFocused();
        if (searchFocused) dismissTranscriptSearchKeyboard();
        if (!outcome.confirmed && (searchFocused || Math.abs(outcome.observedPosition - target.timeMs) > 2500)) {
            PlaybackState retryState = controller.getPlaybackState();
            long retryCurrent = retryState == null ? current : position(retryState);
            List<TranscriptRow> retryRows = readTranscriptRows(screenHeight);
            TranscriptRow retryTarget = adjacentRow(retryRows, retryCurrent, next);
            if (retryTarget == null) retryTarget = findRow(retryRows, target.timeMs);
            if (retryTarget != null) {
                target = retryTarget;
                current = retryCurrent;
                outcome = tapAndConfirm(controller, target, current);
            }
        }
        result.put("targetPosition", target.timeMs).put("observedPosition", outcome.observedPosition)
                .put("confirmed", outcome.confirmed)
                .put("lookupMs", beforeTapStarted - actionStarted)
                .put("tapAndConfirmMs", SystemClock.elapsedRealtime() - beforeTapStarted)
                .put("tapX", target.centerX()).put("tapY", target.centerY())
                .put("visibleTranscriptTimes", transcriptTimes(freshRows));
        if (!outcome.confirmed)
            throw new IllegalStateException("已点选字幕行，但播放器未确认跳转（目标 " + target.timeMs
                    + " 毫秒，当前 " + outcome.observedPosition + " 毫秒，点按位置 "
                    + target.centerX() + "," + target.centerY() + "）");
        return result;
    }

    private static TranscriptRow adjacentRow(List<TranscriptRow> rows, long current, boolean next) {
        TranscriptRow active = null;
        for (TranscriptRow row : rows) {
            if (row.timeMs <= current && (active == null || row.timeMs > active.timeMs)) active = row;
        }
        long reference = active == null ? current : active.timeMs;
        TranscriptRow target = null;
        for (TranscriptRow row : rows) {
            if (next && row.timeMs > reference && (target == null || row.timeMs < target.timeMs)) target = row;
            else if (!next && row.timeMs < reference && (target == null || row.timeMs > target.timeMs)) target = row;
        }
        return target;
    }

    private static TranscriptRow findRow(List<TranscriptRow> rows, long timeMs) {
        for (TranscriptRow row : rows) if (row.timeMs == timeMs) return row;
        return null;
    }

    private static String transcriptTimes(List<TranscriptRow> rows) {
        StringBuilder times = new StringBuilder();
        for (TranscriptRow row : rows) {
            if (times.length() > 0) times.append(',');
            times.append(row.timeMs / 1000);
        }
        return times.toString();
    }

    private TapOutcome tapAndConfirm(MediaController controller, TranscriptRow target, long current)
            throws Exception {
        long started = SystemClock.elapsedRealtime();
        PlaybackState before = controller.getPlaybackState();
        long beforePosition = before == null ? current : position(before);
        float speed = before == null ? 0 : before.getPlaybackSpeed();
        boolean playing = before != null && before.getState() == PlaybackState.STATE_PLAYING;
        runCommand(new String[]{"/system/bin/input", "tap", String.valueOf(target.centerX()),
                String.valueOf(target.centerY())}, 2500);
        long observed = -1;
        boolean confirmed = false;
        for (int sample = 0; sample < 6; sample++) {
            Thread.sleep(75);
            PlaybackState state = controller.getPlaybackState();
            if (state == null) continue;
            observed = position(state);
            long elapsed = SystemClock.elapsedRealtime() - started;
            long naturalPosition = beforePosition + (playing ? (long) (elapsed * speed) : 0);
            long expectedTarget = target.timeMs + (playing ? (long) (elapsed * speed) : 0);
            boolean nearTarget = Math.abs(observed - expectedTarget) <= 1800;
            boolean movedFromNatural = Math.abs(naturalPosition - expectedTarget) < 1000
                    || Math.abs(observed - expectedTarget) + 1000 < Math.abs(observed - naturalPosition);
            if (nearTarget && movedFromNatural) {
                confirmed = true;
                break;
            }
        }
        return new TapOutcome(observed, confirmed);
    }

    private boolean isTranscriptSearchFocused() {
        try {
            AccessibilityNodeInfo root = (AccessibilityNodeInfo) getAutomationRoot.invoke(automation);
            if (root == null) return false;
            ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    CharSequence text = node.getText();
                    CharSequence hint = node.getHintText();
                    CharSequence description = node.getContentDescription();
                    boolean searchLabel = (text != null && text.toString().contains("搜索转写内容"))
                            || (hint != null && hint.toString().contains("搜索转写内容"))
                            || (description != null && description.toString().contains("搜索转写内容"));
                    if (node.isFocused() && (searchLabel || node.isEditable())) return true;
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.addLast(child);
                    }
                } finally { node.recycle(); }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private void dismissTranscriptSearchKeyboard() throws Exception {
        if (!hasTranscriptSearchField() || !isInputMethodVisible()) return;
        // The keyboard can remain visible after YouTube clears focus from its search field.
        // Hide it whenever the transcript search UI is present so it cannot cover row timestamps.
        runCommand(new String[]{"/system/bin/input", "keyevent", "4"}, 1500);
        Thread.sleep(120);
    }

    private boolean hasTranscriptSearchField() {
        try {
            AccessibilityNodeInfo root = (AccessibilityNodeInfo) getAutomationRoot.invoke(automation);
            if (root == null) return false;
            ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    CharSequence text = node.getText();
                    CharSequence hint = node.getHintText();
                    CharSequence description = node.getContentDescription();
                    if ((text != null && text.toString().contains("搜索转写内容"))
                            || (hint != null && hint.toString().contains("搜索转写内容"))
                            || (description != null && description.toString().contains("搜索转写内容")))
                        return true;
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.addLast(child);
                    }
                } finally { node.recycle(); }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean isInputMethodVisible() {
        try {
            List<AccessibilityWindowInfo> windows = (List<AccessibilityWindowInfo>) getAutomationWindows.invoke(automation);
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    try {
                        if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
                    } finally { window.recycle(); }
                }
            }
        } catch (Throwable ignored) { }
        // Some OEM builds omit the IME from UiAutomation.getWindows() while it visibly
        // covers the transcript. Fall back to the system input-method state.
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "input_method")
                    .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("mInputShown=") || line.contains("mShowRequested="))
                        output.append(line).append('\n');
                }
            }
            if (!process.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String state = output.toString();
            return state.contains("mInputShown=true") || state.contains("mShowRequested=true");
        } catch (Throwable ignored) {
            if (process != null) process.destroyForcibly();
            return false;
        }
    }

    private List<TranscriptRow> readTranscriptRows(int screenHeight) throws Exception {
        ensureTranscriptAutomation();
        AccessibilityNodeInfo root;
        try { root = (AccessibilityNodeInfo) getAutomationRoot.invoke(automation); }
        catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException("读取 YouTube 界面失败：" +
                    (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
        }
        if (root == null) throw new IllegalStateException("无法读取当前界面；请保持 YouTube 转写文稿打开");
        Map<Long, TranscriptRow> unique = new LinkedHashMap<>();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            try {
                String description = node.getContentDescription() == null ? null
                        : node.getContentDescription().toString();
                String text = node.getText() == null ? null : node.getText().toString();
                String value = description != null && TRANSCRIPT_TIME.matcher(description).find()
                        ? description : text;
                if (value != null) {
                    Matcher time = TRANSCRIPT_TIME.matcher(value);
                    if (time.find()) {
                        long hours = parsePart(time.group(1));
                        long minutes = parsePart(time.group(2));
                        long seconds = parsePart(time.group(3));
                        long timeMs = ((hours * 60 + minutes) * 60 + seconds) * 1000L;
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        Rect visibleBounds = new Rect(bounds);
                        int screenWidth = systemContext().getResources().getDisplayMetrics().widthPixels;
                        if (visibleBounds.intersect(0, 0, screenWidth, screenHeight)
                                && visibleBounds.top >= screenHeight * 0.44) {
                            TranscriptRow candidate = new TranscriptRow(timeMs, visibleBounds);
                            TranscriptRow existing = unique.get(timeMs);
                            if (existing == null || candidate.area() > existing.area()) unique.put(timeMs, candidate);
                        }
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) pending.addLast(child);
                }
            } finally { node.recycle(); }
        }
        List<TranscriptRow> rows = new ArrayList<>(unique.values());
        rows.sort(Comparator.comparingLong(row -> row.timeMs));
        return rows;
    }

    private static long parsePart(String value) { return value == null ? 0 : Long.parseLong(value); }

    private static void scrollTranscript(List<TranscriptRow> rows, boolean forward, int screenHeight)
            throws Exception {
        int top = screenHeight;
        int bottom = 0;
        for (TranscriptRow row : rows) {
            top = Math.min(top, row.bounds.top);
            bottom = Math.max(bottom, row.bounds.bottom);
        }
        int startY;
        int endY;
        if (forward) {
            startY = Math.min(screenHeight - 220, Math.max(bottom - 100, screenHeight * 3 / 4));
            endY = Math.max(screenHeight / 2, startY - 550);
        } else {
            startY = Math.max(screenHeight / 2, Math.min(top + 120, screenHeight * 2 / 3));
            endY = Math.min(screenHeight - 220, startY + 550);
        }
        if (Math.abs(startY - endY) < 200) throw new IllegalStateException("无法滚动转写文稿列表");
        int x = systemContext().getResources().getDisplayMetrics().widthPixels / 2;
        runCommand(new String[]{"/system/bin/input", "swipe", String.valueOf(x), String.valueOf(startY),
                String.valueOf(x), String.valueOf(endY), "350"}, 2500);
        Thread.sleep(350);
    }

    private static void runCommand(String[] arguments, long timeoutMs) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                while (input.read(buffer) >= 0) { }
            } catch (Exception ignored) { }
        }, "ble-mapper-ui-command-output");
        drain.setDaemon(true);
        drain.start();
        if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            drain.join(300);
            throw new IllegalStateException("读取或操作转写文稿界面超时");
        }
        drain.join(300);
        if (process.exitValue() != 0)
            throw new IllegalStateException("YouTube 转写文稿界面操作失败，退出码 " + process.exitValue());
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
