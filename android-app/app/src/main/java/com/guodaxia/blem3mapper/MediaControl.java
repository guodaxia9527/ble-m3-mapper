package com.guodaxia.blem3mapper;

import android.annotation.SuppressLint;
import android.accessibilityservice.AccessibilityServiceInfo;
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

/** Runs only inside the Shizuku user service, with the verified shell media identity. */
final class MediaControl {
    private static final String YOUTUBE = "com.google.android.youtube";
    private final MediaSessionManager manager;
    private HandlerThread automationLooper;
    private Object automation;
    private Method getAutomationRoot;
    private Method getAutomationWindows;
    private Method clearAutomationCache;
    private Method disconnectAutomation;

    private static final class TranscriptRow {
        final long timeMs;
        TranscriptRow(long timeMs) { this.timeMs = timeMs; }
    }

    private static final class SeekOutcome {
        final long observedPosition;
        final boolean confirmed;
        SeekOutcome(long observedPosition, boolean confirmed) {
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
            AccessibilityServiceInfo serviceInfo = (AccessibilityServiceInfo)
                    uiAutomationClass.getMethod("getServiceInfo").invoke(instance);
            serviceInfo.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            uiAutomationClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class)
                    .invoke(instance, serviceInfo);
            automationLooper = thread;
            automation = instance;
            getAutomationRoot = uiAutomationClass.getMethod("getRootInActiveWindow");
            getAutomationWindows = uiAutomationClass.getMethod("getWindows");
            disconnectAutomation = uiAutomationClass.getMethod("disconnect");
            try { clearAutomationCache = uiAutomationClass.getMethod("clearCache"); }
            catch (NoSuchMethodException ignored) { clearAutomationCache = null; }
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
        clearAutomationCache = null;
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
            ensureTranscriptAutomation();
            if (isTranscriptPanelOpen()) return navigateTranscript(controller, state, action.kind, result);
            int seconds = action.seconds != 0 ? action.seconds
                    : "caption_previous".equals(action.kind) ? -10 : 10;
            result.put("action", "seek").put("seconds", seconds).put("fallback", "no_transcript_panel");
            return seek(controller, state, seconds, result);
        } else if ("transcript_toggle".equals(action.kind)) {
            if (!YOUTUBE.equals(packageName))
                throw new IllegalStateException("开关转写文稿目前仅支持 YouTube");
            ensureTranscriptAutomation();
            return toggleTranscript(controller, result);
        } else if ("seek".equals(action.kind)) {
            return seek(controller, state, action.seconds, result);
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

    private JSONObject seek(MediaController controller, PlaybackState state, int seconds, JSONObject result)
            throws Exception {
        if ((state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0)
            throw new IllegalStateException("此应用的播放器不支持精确跳转");
        long current = position(state);
        long target = Math.max(0, current + seconds * 1000L);
        long duration = duration(controller);
        if (duration > 0) target = Math.min(duration, target);
        SeekOutcome outcome = seekAndConfirm(controller, target, current);
        result.put("seconds", seconds).put("positionBefore", current).put("target", target)
                .put("observedPosition", outcome.observedPosition).put("confirmed", outcome.confirmed);
        return result;
    }

    private interface NodeMatch { boolean matches(AccessibilityNodeInfo node); }

    /** The caller owns the returned node. Never operate on another app's active window. */
    private AccessibilityNodeInfo findUiNode(NodeMatch match) throws Exception {
        if (clearAutomationCache != null) clearAutomationCache.invoke(automation);
        AccessibilityNodeInfo root = (AccessibilityNodeInfo) getAutomationRoot.invoke(automation);
        // A newly connected automation session needs a moment to receive its first window.
        if (root == null) {
            Thread.sleep(100);
            root = (AccessibilityNodeInfo) getAutomationRoot.invoke(automation);
        }
        if (root == null) return null;
        if (!YOUTUBE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            root.recycle();
            throw new IllegalStateException("请保持 YouTube 在前台");
        }
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        try {
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    if (node.isVisibleToUser() && match.matches(node))
                        return AccessibilityNodeInfo.obtain(node);
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.addLast(child);
                    }
                } finally { node.recycle(); }
            }
            return null;
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
    }

    private AccessibilityNodeInfo nodeById(String id) throws Exception {
        return findUiNode(node -> (YOUTUBE + ":id/" + id).equals(node.getViewIdResourceName()));
    }

    private static boolean labelEquals(AccessibilityNodeInfo node, String... labels) {
        for (String label : labels) {
            if (node.getText() != null && label.equalsIgnoreCase(node.getText().toString().trim())) return true;
            if (node.getContentDescription() != null
                    && label.equalsIgnoreCase(node.getContentDescription().toString().trim())) return true;
            if (node.getHintText() != null && label.equalsIgnoreCase(node.getHintText().toString().trim())) return true;
        }
        return false;
    }

    private boolean isTranscriptPanelOpen() throws Exception {
        AccessibilityNodeInfo title = findUiNode(node ->
                ((YOUTUBE + ":id/horizontal_title_row").equals(node.getViewIdResourceName())
                        || (YOUTUBE + ":id/modern_title").equals(node.getViewIdResourceName()))
                        && labelEquals(node, "转写文稿", "Transcript"));
        if (title != null) { title.recycle(); return true; }
        return hasTranscriptSearchField();
    }

    private static boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        try { return node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
        finally { node.recycle(); }
    }

    private boolean clickTranscriptEntry() throws Exception {
        return clickNode(findUiNode(node -> node.isClickable() && labelEquals(node,
                "内容转文字", "显示转写文稿", "查看转写文稿", "Show transcript", "View transcript")));
    }

    private boolean closeSheet() throws Exception {
        AccessibilityNodeInfo close = nodeById("close_button");
        if (close == null) {
            AccessibilityNodeInfo handle = nodeById("arrow_drag_handle");
            if (handle == null) return false;
            Rect header = new Rect(); handle.getBoundsInScreen(header); handle.recycle();
            int maxHeaderHeight = Math.round(100 * systemContext().getResources().getDisplayMetrics().density);
            close = findUiNode(node -> {
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                return node.isClickable() && labelEquals(node, "关闭", "Close")
                        && bounds.top >= header.top && bounds.bottom <= header.bottom + maxHeaderHeight;
            });
        }
        return clickNode(close);
    }

    private boolean waitForTranscript(boolean open, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            if (isTranscriptPanelOpen() == open) return true;
            Thread.sleep(75);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private static String mediaTitle(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        return metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
    }

    private void checkSameVideo(MediaController controller, String title) {
        if (title != null && !title.equals(mediaTitle(controller)))
            throw new IllegalStateException("当前视频已改变，已停止文稿操作");
    }

    private JSONObject toggleTranscript(MediaController controller, JSONObject result) throws Exception {
        long started = SystemClock.elapsedRealtime();
        if (isTranscriptPanelOpen()) {
            dismissTranscriptSearchKeyboard();
            if (!closeSheet() || !waitForTranscript(false, 1200))
                throw new IllegalStateException("无法关闭 YouTube 转写文稿面板");
            return result.put("transcriptOpen", false).put("elapsedMs", SystemClock.elapsedRealtime() - started);
        }
        String title = mediaTitle(controller);
        // An already-open description may have its entry in view.
        if (clickTranscriptEntry()) {
            if (waitForTranscript(true, 5000))
                return result.put("transcriptOpen", true).put("elapsedMs", SystemClock.elapsedRealtime() - started);
            throw new IllegalStateException("文稿尚未载入，请稍后重试");
        }
        AccessibilityNodeInfo sheet = nodeById("arrow_drag_handle");
        if (sheet != null) {
            sheet.recycle();
            if (!closeSheet()) throw new IllegalStateException("请先关闭当前的视频面板");
            Thread.sleep(180);
        }
        checkSameVideo(controller, title);
        if (!openVideoDescription())
            throw new IllegalStateException("请在当前视频的竖屏播放页使用文稿键");
        Thread.sleep(180);
        // This is the description opened from the verified watch-list header. Scroll only
        // its RecyclerView; never swipe recommendations or tap approximate screen positions.
        for (int attempt = 0; attempt < 15; attempt++) {
            checkSameVideo(controller, title);
            if (isTranscriptPanelOpen())
                return result.put("transcriptOpen", true).put("elapsedMs", SystemClock.elapsedRealtime() - started);
            if (clickTranscriptEntry()) {
                if (waitForTranscript(true, 5000))
                    return result.put("transcriptOpen", true).put("elapsedMs", SystemClock.elapsedRealtime() - started);
                throw new IllegalStateException("文稿尚未载入，请稍后重试");
            }
            if (!scrollResults(true)) break;
            Thread.sleep(100);
        }
        throw new IllegalStateException("当前视频简介中未找到转写文稿入口");
    }

    private boolean openVideoDescription() throws Exception {
        AccessibilityNodeInfo player = nodeById("watch_player");
        if (player == null) return false;
        Rect playerBounds = new Rect(); player.getBoundsInScreen(playerBounds); player.recycle();
        AccessibilityNodeInfo list = nodeById("watch_list");
        if (list == null) return false;
        try {
            AccessibilityNodeInfo header = list.getChild(0);
            if (header == null) return false;
            Rect bounds = new Rect(); header.getBoundsInScreen(bounds);
            float density = systemContext().getResources().getDisplayMetrics().density;
            // YouTube's title renderer can have no accessible text. Its first watch-list
            // child is nevertheless a real clickable header, directly beneath the player.
            boolean valid = header.isVisibleToUser() && header.isClickable()
                    && bounds.width() >= playerBounds.width() * 0.9
                    && Math.abs(bounds.top - playerBounds.bottom) <= 20 * density
                    && bounds.height() >= 50 * density && bounds.height() <= 180 * density;
            if (!valid) { header.recycle(); return false; }
            return clickNode(header);
        } finally { list.recycle(); }
    }

    private boolean scrollResults(boolean forward) throws Exception {
        AccessibilityNodeInfo list = nodeById("results");
        if (list == null) return false;
        try { return list.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); }
        finally { list.recycle(); }
    }

    private JSONObject navigateTranscript(MediaController controller, PlaybackState initial,
                                         String action, JSONObject result) throws Exception {
        long actionStarted = SystemClock.elapsedRealtime();
        dismissTranscriptSearchKeyboard();
        boolean next = "caption_next".equals(action);
        long current = position(initial);
        TranscriptRow target = null;
        List<TranscriptRow> rows = new ArrayList<>();
        for (int attempt = 0; attempt < 6; attempt++) {
            rows = readTranscriptRows();
            if (rows.isEmpty()) throw new IllegalStateException("文稿尚未载入字幕行，请稍后再试");
            long first = rows.get(0).timeMs;
            long last = rows.get(rows.size() - 1).timeMs;
            // A manually scrolled list must first include the current playback cue.
            boolean outside = current < first || current > last;
            if (!outside) target = adjacentRow(rows, current, next);
            if (target != null) break;
            boolean scrollForward = current > last || (current >= first && next);
            if (!scrollResults(scrollForward)) {
                // At the end of the list, the last cue can continue to the video's end.
                if (current >= last) target = adjacentRow(rows, current, next);
                break;
            }
            Thread.sleep(100);
        }
        if (target == null)
            throw new IllegalStateException(next ? "未找到下一句字幕" : "未找到上一句字幕");
        long lookupFinished = SystemClock.elapsedRealtime();
        // The timestamp is the meaningful target. A media seek cannot accidentally focus
        // the search box when YouTube scrolls or changes the row's screen coordinates.
        SeekOutcome outcome = seekAndConfirm(controller, target.timeMs, current);
        result.put("targetPosition", target.timeMs).put("observedPosition", outcome.observedPosition)
                .put("confirmed", outcome.confirmed).put("lookupMs", lookupFinished - actionStarted)
                .put("seekAndConfirmMs", SystemClock.elapsedRealtime() - lookupFinished)
                .put("visibleTranscriptTimes", transcriptTimes(rows));
        if (!outcome.confirmed)
            throw new IllegalStateException("播放器尚未确认字幕跳转（目标 " + target.timeMs + " 毫秒）");
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

    private static String transcriptTimes(List<TranscriptRow> rows) {
        StringBuilder times = new StringBuilder();
        for (TranscriptRow row : rows) {
            if (times.length() > 0) times.append(',');
            times.append(row.timeMs / 1000);
        }
        return times.toString();
    }

    private SeekOutcome seekAndConfirm(MediaController controller, long target, long current)
            throws Exception {
        PlaybackState before = controller.getPlaybackState();
        if (before == null || (before.getActions() & PlaybackState.ACTION_SEEK_TO) == 0)
            throw new IllegalStateException("此应用的播放器不支持精确跳转");
        long started = SystemClock.elapsedRealtime();
        long beforePosition = position(before);
        boolean playing = before.getState() == PlaybackState.STATE_PLAYING;
        controller.getTransportControls().seekTo(target);
        long observed = current;
        for (int sample = 0; sample < 10; sample++) {
            Thread.sleep(60);
            PlaybackState state = controller.getPlaybackState();
            if (state == null) continue;
            observed = position(state);
            long elapsed = SystemClock.elapsedRealtime() - started;
            long expected = target + (playing ? (long) (elapsed * before.getPlaybackSpeed()) : 0);
            boolean moved = Math.abs(beforePosition - target) < 500
                    || state.getLastPositionUpdateTime() != before.getLastPositionUpdateTime();
            if (moved && Math.abs(observed - expected) <= 1200) return new SeekOutcome(observed, true);
        }
        return new SeekOutcome(observed, false);
    }

    private void dismissTranscriptSearchKeyboard() throws Exception {
        if (!hasTranscriptSearchField() || !isInputMethodVisible()) return;
        // The keyboard can remain visible after YouTube clears focus from its search field.
        // Hide it whenever the transcript search UI is present so it cannot cover row timestamps.
        runCommand(new String[]{"/system/bin/input", "keyevent", "4"}, 1500);
        Thread.sleep(120);
    }

    private boolean hasTranscriptSearchField() throws Exception {
        AccessibilityNodeInfo search = findUiNode(node -> labelEquals(node,
                "搜索转写内容", "Search transcript", "Search in transcript"));
        if (search == null) return false;
        search.recycle();
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean isInputMethodVisible() throws Exception {
        List<AccessibilityWindowInfo> windows =
                (List<AccessibilityWindowInfo>) getAutomationWindows.invoke(automation);
        boolean shown = false;
        if (windows != null) for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) shown = true;
            window.recycle();
        }
        return shown;
    }

    private List<TranscriptRow> readTranscriptRows() throws Exception {
        AccessibilityNodeInfo root = nodeById("results");
        if (root == null) throw new IllegalStateException("无法读取 YouTube 文稿列表");
        Map<Long, TranscriptRow> unique = new LinkedHashMap<>();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        try {
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    String description = node.getContentDescription() == null ? null
                            : node.getContentDescription().toString();
                    long timeMs = TranscriptTimestampParser.parsePrefix(description);
                    // YouTube exposes each cue as a full accessibility description. Do not
                    // interpret arbitrary caption sentence text as a separate timestamp.
                    if (timeMs >= 0) {
                        unique.put(timeMs, new TranscriptRow(timeMs));
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.addLast(child);
                    }
                } finally { node.recycle(); }
            }
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
        List<TranscriptRow> rows = new ArrayList<>(unique.values());
        rows.sort(Comparator.comparingLong(row -> row.timeMs));
        return rows;
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
