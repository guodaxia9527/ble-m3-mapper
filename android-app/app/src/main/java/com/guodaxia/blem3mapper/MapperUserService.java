package com.guodaxia.blem3mapper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.LocalServerSocket;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Privileged Shizuku binder. The normal application keeps the binding in a foreground service. */
public final class MapperUserService extends IMapperService.Stub {
    private static final String PACKAGE = "com.guodaxia.blem3mapper";
    private static final String TAG = "BleMapper";
    private static final long CENTER_DOUBLE_CLICK_MS = 320;
    private static final Pattern FOCUS = Pattern.compile(
            "mCurrentFocus=Window\\{[^}]*\\s([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/[^}]*\\}");
    private static final Pattern ACTION = Pattern.compile("^ACTION (UP|DOWN|LEFT|RIGHT|CENTER|PHOTO)(?: .*)?$");
    private static final int MAX_LOG_LINES = 80;
    private static final String RUN_SOCKET = PACKAGE + ".active-mapping.v1";
    private final Object lifecycle = new Object();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final Context context;
    private volatile Run active;
    private volatile String idleMessage = "尚未开始映射";

    public MapperUserService() { this(null); }
    public MapperUserService(Context context) { this.context = context; }

    private static final class Line {
        final Process process;
        final String value;
        final long receivedAt;
        Line(Process process, String value) {
            this.process = process;
            this.value = value;
            this.receivedAt = SystemClock.elapsedRealtime();
        }
    }

    private static final class PendingCenter {
        final Process process;
        final String packageName;
        PendingCenter(Process process, String packageName) {
            this.process = process;
            this.packageName = packageName;
        }
    }

    private static final class Focus {
        String packageName = "";
        boolean awake;
        boolean awakeKnown;
        boolean locked = true;
        boolean lockKnown;
        boolean valid;
        String error = "";

        String availablePackage() { return valid && awake && !locked ? packageName : ""; }
    }

    private static final class Run {
        final String capturePath;
        final ProfileConfig config;
        final ArrayBlockingQueue<Line> lines = new ArrayBlockingQueue<>(256);
        volatile boolean stopping;
        volatile boolean connected;
        volatile String foreground = "";
        volatile String profile = "";
        volatile String message = "正在启动";
        volatile Process capture;
        volatile Process focusProcess;
        volatile String readerFailure;
        long nextAutomationAttempt;
        Thread worker;
        Ownership ownership;
        long retryAfter;

        Run(String capturePath, ProfileConfig config) {
            this.capturePath = capturePath;
            this.config = config;
        }
    }

    /** The shell process has no Android service lifecycle of its own. Tie it to both owners. */
    private final class Ownership {
        final Run run;
        final IBinder client;
        final IBinder shizuku;
        final IBinder.DeathRecipient clientDeath;
        final IBinder.DeathRecipient shizukuDeath;
        volatile boolean closed;
        volatile boolean died;
        private boolean clientLinked;
        private boolean shizukuLinked;
        private LocalServerSocket socket;

        Ownership(Run run, IBinder client, IBinder shizuku) {
            this.run = run;
            this.client = client;
            this.shizuku = shizuku;
            clientDeath = () -> ownerDied(this, "映射应用已退出");
            shizukuDeath = () -> ownerDied(this, "Shizuku 已停止或重新启动");
        }

        synchronized void attach() throws Exception {
            if (client == null || shizuku == null || !client.isBinderAlive() || !shizuku.isBinderAlive())
                throw new IllegalStateException("控制连接已失效，请重新开始映射");
            try {
                // Abstract socket lifetime is process scoped; no stale filesystem lock remains.
                socket = new LocalServerSocket(RUN_SOCKET);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("已有映射服务正在运行，请先停止旧映射再重试");
            }
            try {
                client.linkToDeath(clientDeath, 0);
                clientLinked = true;
                shizuku.linkToDeath(shizukuDeath, 0);
                shizukuLinked = true;
                // Close registration vs. owner-death races before any worker can grab input.
                if (died || !client.isBinderAlive() || !shizuku.isBinderAlive())
                    throw new IllegalStateException("控制连接已失效，请重新开始映射");
            } catch (Exception error) {
                close();
                throw error;
            }
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            if (clientLinked) {
                try { client.unlinkToDeath(clientDeath, 0); } catch (RuntimeException ignored) { }
                clientLinked = false;
            }
            if (shizukuLinked) {
                try { shizuku.unlinkToDeath(shizukuDeath, 0); } catch (RuntimeException ignored) { }
                shizukuLinked = false;
            }
            if (socket != null) {
                try { socket.close(); } catch (java.io.IOException ignored) { }
                socket = null;
            }
        }
    }

    private void ownerDied(Ownership ownership, String reason) {
        if (ownership.closed) return;
        ownership.died = true;
        Run run = ownership.run;
        // Do this before waiting for a lifecycle lock or a binder call to return.
        run.stopping = true;
        run.connected = false;
        terminate(run.capture);
        terminate(run.focusProcess);
        if (run.worker != null) run.worker.interrupt();
        synchronized (lifecycle) {
            // A delayed death callback from a normally stopped run cannot kill its replacement.
            if (active != run) return;
            idleMessage = reason + "，已释放遥控器";
            log(idleMessage);
            stopCurrent();
            System.exit(0);
        }
    }

    @Override public String startMapping(String capturePath, String configJson,
                                         IBinder clientToken, IBinder shizukuToken) {
        synchronized (lifecycle) {
            Run run = null;
            try {
                ProfileConfig config = ProfileConfig.parse(configJson);
                if (config.profiles.isEmpty()) throw new IllegalArgumentException("请先添加一个应用配置");
                String verifiedPath = verifyCapturePath(capturePath);
                if (!stopCurrent()) throw new IllegalStateException("上一次映射还在停止，请稍后重试");
                run = new Run(verifiedPath, config);
                Run capturedRun = run;
                run.worker = new Thread(() -> work(capturedRun), "ble-mapper-control");
                run.ownership = new Ownership(run, clientToken, shizukuToken);
                active = run;
                run.ownership.attach();
                if (run.stopping || run.ownership.died)
                    throw new IllegalStateException("控制连接已失效，请重新开始映射");
                run.worker.start();
                return new JSONObject().put("ok", true).put("servicePid", android.os.Process.myPid()).toString();
            } catch (Exception error) {
                if (run != null && active == run) stopCurrent();
                String detail = errorMessage(error);
                log("启动失败：" + detail);
                if (active == null || active.stopping) idleMessage = detail;
                return errorJson(detail);
            }
        }
    }

    private String verifyCapturePath(String requested) throws Exception {
        Context lookup = context != null ? context : MediaControl.systemContext();
        ApplicationInfo info = lookup.getPackageManager().getApplicationInfo(PACKAGE, 0);
        File expected = new File(info.nativeLibraryDir, "libble_m3_capture.so").getCanonicalFile();
        if (requested == null || !expected.equals(new File(requested).getCanonicalFile()) ||
                !expected.isFile() || !expected.canExecute())
            throw new IllegalArgumentException("按键识别组件不可用，请重新安装此应用");
        return expected.getAbsolutePath();
    }

    @Override public void stopMapping() {
        synchronized (lifecycle) {
            boolean stopped = stopCurrent();
            idleMessage = stopped ? "映射已停止，遥控器已恢复原有操作" : "正在停止映射";
        }
    }

    /** Called with lifecycle held. Native grabbing ends before waiting for the Java worker. */
    private boolean stopCurrent() {
        Run run = active;
        if (run == null) return true;
        run.stopping = true;
        run.connected = false;
        terminate(run.capture);
        terminate(run.focusProcess);
        if (run.worker != null) {
            run.worker.interrupt();
            try { run.worker.join(2500); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            if (run.worker.isAlive()) return false;
        }
        if (run.capture != null && run.capture.isAlive()) return false;
        if (run.ownership != null) run.ownership.close();
        active = null;
        log("映射已停止，遥控器已释放");
        return true;
    }

    @Override public String getStatus() {
        Run run = active;
        try {
            return new JSONObject()
                    .put("servicePid", android.os.Process.myPid())
                    .put("running", run != null && !run.stopping && run.worker.isAlive())
                    .put("connected", run != null && run.connected && !run.stopping)
                    .put("foreground", run == null ? "" : run.foreground)
                    .put("profile", run == null ? "" : run.profile)
                    .put("message", run == null || run.stopping ? idleMessage : run.message).toString();
        } catch (Exception ignored) {
            return "{\"running\":false,\"connected\":false,\"message\":\"状态暂不可用\"}";
        }
    }

    @Override public String getLogs() {
        synchronized (logs) { return String.join("\n", logs); }
    }

    @Override public void destroy() {
        stopMapping();
        System.exit(0);
    }

    private void work(Run run) {
        MediaControl media = null;
        try {
            media = new MediaControl();
            log("手机端映射已启动；只在已配置的应用中接管遥控器");
            long nextFocus = 0;
            String capturePackage = "";
            CenterClickTracker<PendingCenter> centerClicks = new CenterClickTracker<>(CENTER_DOUBLE_CLICK_MS);
            while (!run.stopping) {
                long now = SystemClock.elapsedRealtime();
                CenterClickTracker.Click<PendingCenter> completedCenter;
                // Serialize the deadline decision with timestamping/enqueueing by the reader.
                synchronized (run.lines) {
                    completedCenter = centerClicks.expire(now, !run.lines.isEmpty());
                }
                if (completedCenter != null) {
                    dispatchCenterClick(run, media, completedCenter, capturePackage);
                    nextFocus = 0;
                }
                // Foreground polling can take longer than the double-click window. Resolve
                // buffered gestures first; every resulting action still validates focus below.
                if (!centerClicks.hasPending() && run.lines.isEmpty() && now >= nextFocus) {
                    Focus focus = readFocus(run);
                    if (run.stopping) break;
                    updateFocus(run, focus);
                    String desired = focus.availablePackage();
                    ProfileConfig.Profile profile = run.config.profiles.get(desired);
                    if (profile == null) {
                        if (run.capture != null) stopCapture(run);
                        capturePackage = "";
                        run.profile = "";
                        if (!focus.valid) status(run, "无法确认当前应用，已暂时释放遥控器");
                        else if (focus.locked || !focus.awake) status(run, "手机已锁屏或熄屏，遥控器保持原有操作");
                        else status(run, "等待打开已配置的应用；遥控器保持原有操作");
                    } else {
                        run.profile = profile.name;
                        if (needsTranscriptAutomation(profile) && !media.isTranscriptAutomationReady()
                                && now >= run.nextAutomationAttempt) {
                            run.nextAutomationAttempt = now + 5000;
                            try {
                                media.prepareTranscriptAutomation();
                                log("YouTube 字幕快速控制已就绪");
                            } catch (Exception error) {
                                log("字幕快速控制暂不可用：" + errorMessage(error));
                            }
                        }
                        if (!desired.equals(capturePackage)) {
                            stopCapture(run);
                            capturePackage = desired;
                            run.retryAfter = 0;
                        }
                        if (run.capture == null && now >= run.retryAfter) startCapture(run);
                    }
                    nextFocus = SystemClock.elapsedRealtime() + 500;
                }
                if (run.readerFailure != null) throw new IllegalStateException(run.readerFailure);
                long pollMs = centerClicks.pollMillis(SystemClock.elapsedRealtime(), 50);
                Line line = run.lines.poll(pollMs, TimeUnit.MILLISECONDS);
                if (line == null || line.process != run.capture) continue;
                if (line.value == null) {
                    centerClicks.cancel();
                    int exit = line.process.isAlive() ? -1 : line.process.exitValue();
                    stopCapture(run);
                    run.retryAfter = SystemClock.elapsedRealtime() + 2500;
                    status(run, "接管暂时中断，正在重试（" + exit + "）");
                    continue;
                }
                Matcher action = ACTION.matcher(line.value);
                if (action.matches()) {
                    String button = action.group(1);
                    ProfileConfig.Profile capturedProfile = run.config.profiles.get(capturePackage);
                    if ("CENTER".equals(button) && "com.google.android.youtube".equals(capturePackage)
                            && capturedProfile != null
                            && "toggle".equals(capturedProfile.buttons.get("CENTER").kind)) {
                        completedCenter = centerClicks.press(line.process,
                                new PendingCenter(line.process, capturePackage), line.receivedAt);
                        if (completedCenter != null) {
                            dispatchCenterClick(run, media, completedCenter, capturePackage);
                            nextFocus = 0;
                        }
                        continue;
                    }
                    // A different physical button ends the pair; preserve its order after
                    // the preceding single center click rather than combining across it.
                    completedCenter = centerClicks.takeSingle();
                    if (completedCenter != null) dispatchCenterClick(run, media, completedCenter, capturePackage);
                    // Refresh on each completed physical gesture; do not act on stale foreground data.
                    Focus focus = readFocus(run);
                    if (run.stopping) break;
                    updateFocus(run, focus);
                    String target = focus.availablePackage();
                    ProfileConfig.Profile profile = run.config.profiles.get(target);
                    if (profile == null || !target.equals(capturePackage)) {
                        log("应用已切换，忽略这次按键并释放遥控器");
                        stopCapture(run);
                        capturePackage = "";
                        nextFocus = 0;
                        continue;
                    }
                    handleAction(run, media, profile, button);
                } else if (line.value.startsWith("LISTENER_READY")) {
                    run.connected = true;
                    status(run, "已接管 BLE-M3 · " + run.profile);
                } else if (line.value.startsWith("WAITING_FOR_DEVICE") ||
                        line.value.startsWith("DEVICE_DISCONNECTED") ||
                        line.value.startsWith("DEVICE_READ_INTERRUPTED")) {
                    centerClicks.cancel();
                    run.connected = false;
                    status(run, "等待 BLE-M3 连接或唤醒 · " + run.profile);
                } else if (line.value.startsWith("GRAB_FAILED")) {
                    run.connected = false;
                    Log.w(TAG, line.value);
                    throw new IllegalStateException(line.value.contains("errno=16 ")
                            ? "遥控器正被另一控制服务占用，请停止旧服务后重新开始映射"
                            : "无法接管遥控器，请检查 Shizuku 权限后重新开始映射");
                } else if (line.value.startsWith("ERROR") || line.value.startsWith("WARNING")) {
                    if (line.value.startsWith("WARNING input queue overflow")) centerClicks.cancel();
                    Log.w(TAG, line.value);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            idleMessage = "映射已停止：" + errorMessage(error);
            log(idleMessage);
            Log.e(TAG, "Mapping stopped", error);
        } finally {
            run.stopping = true;
            boolean captureStopped = stopCapture(run);
            terminate(run.focusProcess);
            if (media != null) media.close();
            // No more capture can be started after this point. Keep exclusivity through cleanup.
            if (captureStopped && run.ownership != null) run.ownership.close();
        }
    }

    private static boolean needsTranscriptAutomation(ProfileConfig.Profile profile) {
        for (ProfileConfig.Action action : profile.buttons.values()) {
            if ("caption_previous".equals(action.kind) || "caption_next".equals(action.kind)
                    || "transcript_toggle".equals(action.kind)) return true;
        }
        return false;
    }

    private void dispatchCenterClick(Run run, MediaControl media,
                                     CenterClickTracker.Click<PendingCenter> click,
                                     String capturePackage) throws InterruptedException {
        PendingCenter pending = click.context;
        if (run.stopping || pending.process != run.capture || !pending.packageName.equals(capturePackage)) return;
        Focus focus = readFocus(run);
        if (run.stopping) return;
        updateFocus(run, focus);
        if (!pending.packageName.equals(focus.availablePackage())) {
            log("中键操作已取消：当前应用已切换");
            return;
        }
        ProfileConfig.Profile profile = run.config.profiles.get(pending.packageName);
        if (profile == null) return;
        if (click.doubleClick) {
            handleAction(run, media, profile, "CENTER", "like");
        } else {
            handleAction(run, media, profile, "CENTER");
        }
    }

    private void updateFocus(Run run, Focus focus) {
        run.foreground = focus.valid ? focus.packageName : "";
        if (!focus.valid && !focus.error.isEmpty()) Log.w(TAG, "Focus unavailable: " + focus.error);
    }

    private void startCapture(Run run) throws Exception {
        if (run.stopping) return;
        run.lines.clear();
        Process child = new ProcessBuilder(run.capturePath, "--listen", "--seconds", "0")
                .redirectErrorStream(true).start();
        run.capture = child;
        if (run.stopping) { terminate(child); return; }
        status(run, "正在连接 BLE-M3 · " + run.profile);
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    child.getInputStream(), StandardCharsets.UTF_8))) {
                String value;
                while (!run.stopping && (value = input.readLine()) != null) {
                    boolean offered;
                    synchronized (run.lines) {
                        offered = run.lines.offer(new Line(child, value));
                    }
                    if (!offered) {
                        run.readerFailure = "按键事件积压，已停止接管，请重新开始";
                        terminate(child);
                        break;
                    }
                }
            } catch (Exception error) {
                if (!run.stopping && run.capture == child) Log.w(TAG, "Capture reader stopped", error);
            } finally {
                synchronized (run.lines) {
                    run.lines.offer(new Line(child, null));
                }
            }
        }, "ble-mapper-input");
        reader.setDaemon(true);
        reader.start();
    }

    private static boolean stopCapture(Run run) {
        Process child = run.capture;
        run.connected = false;
        boolean stopped = terminate(child);
        if (stopped && run.capture == child) run.capture = null;
        run.lines.clear();
        return stopped;
    }

    private static boolean terminate(Process process) {
        if (process == null) return true;
        boolean interrupted = Thread.interrupted();
        try {
            process.destroy();
            long deadline = SystemClock.elapsedRealtime() + 750;
            boolean forced = false;
            while (process.isAlive() && SystemClock.elapsedRealtime() < deadline) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                try {
                    if (process.waitFor(Math.max(1, Math.min(forced ? 500 : 150, remaining)),
                            TimeUnit.MILLISECONDS)) break;
                } catch (InterruptedException ignored) { interrupted = true; }
                if (process.isAlive()) { process.destroyForcibly(); forced = true; }
            }
        } catch (Throwable ignored) { }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
        return !process.isAlive();
    }

    private void handleAction(Run run, MediaControl media, ProfileConfig.Profile profile, String button)
            throws InterruptedException {
        handleAction(run, media, profile, button, null);
    }

    private void handleAction(Run run, MediaControl media, ProfileConfig.Profile profile, String button,
                              String forcedKind) throws InterruptedException {
        ProfileConfig.Action configured = profile.buttons.get(button);
        ProfileConfig.Action action = forcedKind == null ? configured : new ProfileConfig.Action(forcedKind, 0);
        String prefix = "CENTER".equals(button) && "like".equals(forcedKind)
                ? profile.name + " · 中键双击：" : profile.name + " · " + ProfileConfig.label(button) + "：";
        if ("none".equals(action.kind)) {
            log(prefix + "未分配操作");
            return;
        }
        try {
            if (run.stopping) return;
            JSONObject result = media.perform(profile.packageName, action);
            String description;
            if ("seek".equals(action.kind)) {
                description = (action.seconds < 0 ? "后退 " : "前进 ") + Math.abs(action.seconds) + " 秒";
            } else if ("caption_previous".equals(action.kind)) {
                description = result.has("fallback") ? "转写文稿未打开，后退 "
                        + Math.abs(result.optInt("seconds", action.seconds)) + " 秒"
                        : "跳到上一条字幕 " + formatCaptionTime(result.optLong("targetPosition", -1));
            } else if ("caption_next".equals(action.kind)) {
                description = result.has("fallback") ? "转写文稿未打开，前进 "
                        + Math.abs(result.optInt("seconds", action.seconds)) + " 秒"
                        : "跳到下一条字幕 " + formatCaptionTime(result.optLong("targetPosition", -1));
            } else if ("transcript_toggle".equals(action.kind)) {
                description = result.optBoolean("transcriptOpen") ? "已打开转写文稿" : "已关闭转写文稿";
            } else if ("like".equals(action.kind)) {
                description = result.optBoolean("alreadyLiked") ? "已经赞过，保持点赞"
                        : result.optBoolean("confirmed") ? "已点赞" : "已发送点赞，请在 YouTube 中确认";
            } else {
                description = result.optInt("requestedState") == 3 ? "播放" : "暂停";
            }
            log(prefix + description);
            Log.i(TAG, "RESULT button=" + button + " " + result);
        } catch (InterruptedException error) {
            throw error;
        } catch (Exception error) {
            log(prefix + errorMessage(error));
        }
    }

    private Focus readFocus(Run run) throws InterruptedException {
        Focus focus = new Focus();
        Process process = null;
        Thread scanner = null;
        try {
            // Fixed argv only. Do not interpolate configuration or button data into a shell command.
            process = new ProcessBuilder("/system/bin/dumpsys", "-t", "1", "window")
                    .redirectErrorStream(true).start();
            run.focusProcess = process;
            if (run.stopping) return focus;
            final Process readerProcess = process;
            scanner = new Thread(() -> scanFocus(readerProcess, focus), "ble-mapper-focus");
            scanner.setDaemon(true);
            scanner.start();
            if (!process.waitFor(1300, TimeUnit.MILLISECONDS)) {
                focus.error = "读取窗口超时";
                return focus;
            }
            scanner.join(300);
            if (scanner.isAlive() || process.exitValue() != 0 || !focus.error.isEmpty() ||
                    !focus.lockKnown || !focus.awakeKnown) {
                focus.error = "窗口或锁屏状态暂不可用";
                return focus;
            }
            focus.valid = true;
        } catch (InterruptedException error) {
            throw error;
        } catch (Exception error) {
            focus.error = errorMessage(error);
        } finally {
            terminate(process);
            if (run.focusProcess == process) run.focusProcess = null;
        }
        return focus;
    }

    private static String formatCaptionTime(long positionMs) {
        if (positionMs < 0) return "";
        long totalSeconds = positionMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private static void scanFocus(Process process, Focus focus) {
        boolean inKeyguard = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = FOCUS.matcher(line);
                if (matcher.find()) focus.packageName = matcher.group(1);
                String trimmed = line.trim();
                if (line.contains("mAwake=")) {
                    focus.awakeKnown = true;
                    focus.awake = line.contains("mAwake=true");
                }
                if (trimmed.equals("KeyguardServiceDelegate")) inKeyguard = true;
                if (inKeyguard && (trimmed.equals("showing=true") || trimmed.equals("showing=false") ||
                        trimmed.equals("mIsShowing=true") || trimmed.equals("mIsShowing=false"))) {
                    focus.lockKnown = true;
                    focus.locked = trimmed.endsWith("=true");
                }
                if (inKeyguard && trimmed.startsWith("interactiveState=")) {
                    focus.awakeKnown = true;
                    focus.awake = trimmed.equals("interactiveState=INTERACTIVE_STATE_AWAKE");
                }
                if (inKeyguard && trimmed.startsWith("Looper state:")) inKeyguard = false;
            }
        } catch (Exception error) { focus.error = errorMessage(error); }
    }

    private void status(Run run, String message) {
        if (!message.equals(run.message)) {
            run.message = message;
            log(message);
        }
    }

    private void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date());
        synchronized (logs) {
            logs.addLast(time + "  " + message);
            while (logs.size() > MAX_LOG_LINES) logs.removeFirst();
        }
        Log.i(TAG, message);
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String errorJson(String message) {
        try { return new JSONObject().put("ok", false).put("error", message).toString(); }
        catch (Exception ignored) { return "{\"ok\":false,\"error\":\"启动失败\"}"; }
    }
}
