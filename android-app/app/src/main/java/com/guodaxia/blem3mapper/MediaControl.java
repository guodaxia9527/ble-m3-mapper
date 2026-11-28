package com.guodaxia.blem3mapper;

import android.annotation.SuppressLint;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
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
    private volatile long lastTranscriptScroll = -1000;
    private MediaSession.Token videoSession;
    private String videoIdentity;
    private PlaybackState previousPlayback;
    private boolean jumpedByRemote;
    private long segmentStart = -1;
    private long segmentEnd = -1;
    private long playingFrom = -1;
    private long replayUntil = -1;
    private long replayFrom;
    private long replayStarted;
    private boolean replayWasPlaying;

    private static final class TranscriptRow {
        final long timeMs;
        final Rect bounds;
        final boolean selected;
        TranscriptRow(long timeMs, Rect bounds, boolean selected) {
            this.timeMs = timeMs;
            this.bounds = bounds;
            this.selected = selected;
        }
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
            serviceInfo.eventTypes |= AccessibilityEvent.TYPE_VIEW_SCROLLED;
            uiAutomationClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class)
                    .invoke(instance, serviceInfo);
            ((UiAutomation) instance).setOnAccessibilityEventListener(event -> {
                if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
                        && event.getPackageName() != null && YOUTUBE.contentEquals(event.getPackageName())) {
                    lastTranscriptScroll = SystemClock.elapsedRealtime();
                }
            });
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

    void clearPlaybackHistory() {
        videoSession = null;
        videoIdentity = null;
        previousPlayback = null;
        jumpedByRemote = false;
        playingFrom = -1;
        segmentStart = -1;
        segmentEnd = -1;
        replayUntil = -1;
    }

    private static String videoIdentity(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return null;
        String id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id != null && !id.isEmpty()) return "id:" + id;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        long length = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        return title == null || title.isEmpty() || length <= 0 ? null : "title:" + title + "\n" + length;
    }

    boolean observeYoutubePlayback() {
        try {
            MediaController controller = controller(YOUTUBE);
            return observePlayback(controller, controller.getPlaybackState());
        } catch (RuntimeException error) {
            clearPlaybackHistory();
            return false;
        }
    }

    private boolean observePlayback(MediaController controller, PlaybackState state) {
        String identity = videoIdentity(controller);
        if (identity == null || state == null || state.getPosition() < 0) {
            clearPlaybackHistory();
            return false;
        }
        if (!controller.getSessionToken().equals(videoSession) || !identity.equals(videoIdentity)) {
            clearPlaybackHistory();
            videoSession = controller.getSessionToken();
            videoIdentity = identity;
        }
        PlaybackState previous = previousPlayback;
        previousPlayback = state;
        boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
        if (replayUntil >= 0) {
            if (playing) replayWasPlaying = true;
            if (replayWasPlaying && playing && jumped(previous, state)) {
                invalidateSegment();
            } else if (playing && position(state) >= replayUntil) {
                return true;
            } else if ((state.getState() == PlaybackState.STATE_PAUSED && replayWasPlaying)
                    || (state.getState() == PlaybackState.STATE_STOPPED)
                    || (!replayWasPlaying && SystemClock.elapsedRealtime() - replayStarted > 3000)) {
                replayUntil = -1;
            }
            return false;
        }
        if (!jumpedByRemote) return false;
        if (jumped(previous, state) || (previous != null
                && previous.getState() == PlaybackState.STATE_PAUSED
                && Math.abs(previous.getPosition() - state.getPosition()) > 1500)) {
            invalidateSegment();
            return false;
        }
        if (playing) {
            if (playingFrom < 0) {
                playingFrom = previous != null && previous.getState() == PlaybackState.STATE_PAUSED
                        ? previous.getPosition() : state.getPosition();
            }
        } else if (state.getState() == PlaybackState.STATE_PAUSED) {
            if (playingFrom >= 0 && state.getPosition() - playingFrom >= 500) {
                segmentStart = playingFrom;
                segmentEnd = state.getPosition();
            } else if (playingFrom >= 0) {
                segmentStart = -1;
                segmentEnd = -1;
            }
            playingFrom = -1;
        } else if (state.getState() == PlaybackState.STATE_STOPPED) {
            invalidateSegment();
        } else if (state.getState() != PlaybackState.STATE_BUFFERING) {
            playingFrom = -1;
        }
        return false;
    }

    private void invalidateSegment() {
        jumpedByRemote = false;
        playingFrom = -1;
        segmentStart = -1;
        segmentEnd = -1;
        replayUntil = -1;
    }

    private static boolean jumped(PlaybackState previous, PlaybackState current) {
        if (previous == null || previous.getState() != PlaybackState.STATE_PLAYING
                || previous.getLastPositionUpdateTime() <= 0) return false;
        long elapsed = current.getLastPositionUpdateTime() - previous.getLastPositionUpdateTime();
        long expected = previous.getPosition() + (long) (Math.max(0, elapsed) * previous.getPlaybackSpeed());
        return Math.abs(current.getPosition() - expected) > 1500;
    }

    boolean isLooping() { return replayUntil >= 0; }

    void repeatSegment() throws Exception {
        if (replayUntil < 0) return;
        MediaController controller = controller(YOUTUBE);
        PlaybackState state = controller.getPlaybackState();
        if (!observePlayback(controller, state)) return;
        SeekOutcome outcome = seekAndConfirm(controller, replayFrom, position(state), 300);
        MediaController active = controller(YOUTUBE);
        PlaybackState current = active.getPlaybackState();
        if (!outcome.confirmed || !isSameVideo(active) || current == null
                || current.getState() == PlaybackState.STATE_PAUSED
                || current.getState() == PlaybackState.STATE_STOPPED) {
            replayUntil = -1;
            if (!outcome.confirmed) throw new IllegalStateException("播放器没有确认回到循环起点");
            return;
        }
        previousPlayback = current;
    }

    private JSONObject replay(MediaController controller, PlaybackState state, JSONObject result,
                              boolean loopTranscriptLine) throws Exception {
        if (state.getState() != PlaybackState.STATE_PAUSED)
            return result.put("message", "暂停后再双击重播");
        if (!isSameVideo(controller))
            return result.put("message", "无法确认当前视频，暂时不能循环");
        long start = segmentStart;
        long end = segmentEnd;
        boolean sentence = false;
        if (loopTranscriptLine) {
            ensureTranscriptAutomation();
            if (isTranscriptPanelOpen()) {
                long[] range = visibleSentenceRange(position(state));
                if (range == null)
                    return result.put("message", "请让当前句和下一句出现在文稿列表中，再双击中键");
                start = range[0];
                end = range[1];
                sentence = true;
            }
        }
        if (!sentence && (!jumpedByRemote || start < 0))
            return result.put("message", "请先用遥控器跳转，播放一段再暂停");
        if ((state.getActions() & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) == 0)
            throw new IllegalStateException("播放器当前不支持继续播放");
        PlaybackState beforeSeek = controller.getPlaybackState();
        if (!isSameVideo(controller) || beforeSeek == null
                || beforeSeek.getState() != PlaybackState.STATE_PAUSED)
            return result.put("message", "播放状态已改变，请暂停后重试");
        SeekOutcome outcome = seekAndConfirm(controller, start, position(beforeSeek), 300);
        MediaController active = controller(YOUTUBE);
        PlaybackState current = active.getPlaybackState();
        if (!outcome.confirmed || !isSameVideo(active) || current == null
                || current.getState() != PlaybackState.STATE_PAUSED) {
            throw new IllegalStateException("无法确认重播起点，保持当前播放状态");
        }
        replayUntil = end;
        replayFrom = start;
        replayStarted = SystemClock.elapsedRealtime();
        replayWasPlaying = false;
        previousPlayback = current;
        playingFrom = -1;
        controller.getTransportControls().play();
        return result.put("replayStart", start).put("replayEnd", end).put("sentenceLoop", sentence);
    }

    JSONObject perform(String packageName, ProfileConfig.Action action) throws Exception {
        return perform(packageName, action, false);
    }

    JSONObject perform(String packageName, ProfileConfig.Action action, boolean loopTranscriptLine) throws Exception {
        if ("like".equals(action.kind) && !"com.google.android.youtube".equals(packageName))
            throw new IllegalStateException("点赞目前仅支持 YouTube，此应用尚未适配");
        MediaController controller = controller(packageName);
        PlaybackState state = controller.getPlaybackState();
        if (state == null) throw new IllegalStateException("播放器没有提供播放状态");
        JSONObject result = new JSONObject().put("package", packageName)
                .put("action", action.kind).put("stateBefore", state.getState());
        if (YOUTUBE.equals(packageName)) {
            observePlayback(controller, state);
            if ("replay".equals(action.kind)) return replay(controller, state, result, loopTranscriptLine);
            if ("toggle".equals(action.kind) && replayUntil >= 0) {
                replayUntil = -1;
            }
            if ("seek_start".equals(action.kind) || "seek_end".equals(action.kind)) {
                if (state.getState() != PlaybackState.STATE_PAUSED)
                    return result.put("message", "暂停后再三击跳到首尾");
                long length = duration(controller);
                if ("seek_end".equals(action.kind) && length <= 0)
                    throw new IllegalStateException("播放器未提供视频时长");
                return seekTo(controller, state, "seek_start".equals(action.kind) ? 0 : Math.max(0, length - 1500), result);
            }
        }
        if (state.getState() != PlaybackState.STATE_PAUSED && state.getState() != PlaybackState.STATE_PLAYING
                && state.getState() != PlaybackState.STATE_STOPPED)
            throw new IllegalStateException("播放器当前状态暂不支持此操作（状态码 " + state.getState() + "）");
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
        return seekTo(controller, state, target, result.put("seconds", seconds));
    }

    private JSONObject seekTo(MediaController controller, PlaybackState state, long target, JSONObject result)
            throws Exception {
        long current = position(state);
        SeekOutcome outcome = seekFromRemote(controller, target, current);
        result.put("positionBefore", current).put("target", target)
                .put("observedPosition", outcome.observedPosition).put("confirmed", outcome.confirmed);
        return result;
    }

    private boolean isSameVideo(MediaController controller) {
        return controller.getSessionToken().equals(videoSession)
                && videoIdentity != null && videoIdentity.equals(videoIdentity(controller));
    }

    private SeekOutcome seekFromRemote(MediaController controller, long target, long current)
            throws Exception {
        boolean youtube = YOUTUBE.equals(controller.getPackageName());
        if (youtube) {
            invalidateSegment();
            previousPlayback = null;
        }
        SeekOutcome outcome = seekAndConfirm(controller, target, current);
        if (youtube && outcome.confirmed && target != current && isSameVideo(controller)) {
            PlaybackState postSeek = controller.getPlaybackState();
            if (postSeek != null && postSeek.getPosition() >= 0) {
                jumpedByRemote = true;
                previousPlayback = postSeek;
                if (postSeek.getState() == PlaybackState.STATE_PLAYING) playingFrom = target;
            }
        }
        return outcome;
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
            rows = readTranscriptRows(false);
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
        SeekOutcome outcome = seekFromRemote(controller, target.timeMs, current);
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

    private long[] visibleSentenceRange(long positionMs) throws Exception {
        List<TranscriptRow> rows = readTranscriptRows(true);
        for (int i = 1; i < rows.size(); i++) {
            TranscriptRow current = rows.get(i - 1);
            TranscriptRow next = rows.get(i);
            if (current.timeMs <= positionMs && positionMs < next.timeMs
                    && current.bounds != null && next.bounds != null) {
                return new long[]{current.timeMs, next.timeMs};
            }
        }
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

    private SeekOutcome seekAndConfirm(MediaController controller, long target, long current)
            throws Exception {
        return seekAndConfirm(controller, target, current, 1200);
    }

    private SeekOutcome seekAndConfirm(MediaController controller, long target, long current, long tolerance)
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
            if (moved && Math.abs(observed - expected) <= tolerance) return new SeekOutcome(observed, true);
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

    Rect currentTranscriptBounds() throws Exception {
        if (transcriptIsScrolling()) return null;
        if (!isTranscriptAutomationReady() || !isTranscriptPanelOpen() || isInputMethodVisible()) return null;
        MediaController player = controller(YOUTUBE);
        PlaybackState state = player.getPlaybackState();
        if (state == null || state.getPosition() < 0) return null;
        int playback = state.getState();
        if (playback != PlaybackState.STATE_PLAYING && playback != PlaybackState.STATE_PAUSED
                && playback != PlaybackState.STATE_BUFFERING) return null;
        long positionMs = position(state);
        List<TranscriptRow> rows = readTranscriptRows(true);
        for (int i = rows.size() - 1; i >= 0; i--) {
            TranscriptRow row = rows.get(i);
            if (row.timeMs > positionMs) continue;
            // A scrolled list may end before the active cue. Only mark its last row
            // when YouTube identifies it or the player is at that row's timestamp.
            if (i == rows.size() - 1 && !row.selected && positionMs - row.timeMs >= 1000) return null;
            return transcriptIsScrolling() ? null : row.bounds;
        }
        return null;
    }

    private boolean transcriptIsScrolling() {
        return SystemClock.elapsedRealtime() - lastTranscriptScroll < 300;
    }

    private List<TranscriptRow> readTranscriptRows(boolean includeBounds) throws Exception {
        AccessibilityNodeInfo root = nodeById("results");
        if (root == null) throw new IllegalStateException("无法读取 YouTube 文稿列表");
        Map<Long, TranscriptRow> unique = new LinkedHashMap<>();
        Rect viewport = new Rect();
        if (includeBounds) root.getBoundsInScreen(viewport);
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
                        Rect bounds = null;
                        if (includeBounds && node.isVisibleToUser()) {
                            bounds = new Rect();
                            node.getBoundsInScreen(bounds);
                            if (!bounds.intersect(viewport)) bounds = null;
                        }
                        TranscriptRow previous = unique.get(timeMs);
                        if (previous == null || previous.bounds == null || node.isSelected())
                            unique.put(timeMs, new TranscriptRow(timeMs, bounds, includeBounds && node.isSelected()));
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
