package com.guodaxia.blem3mapper;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** Application-owned binding; the worker also watches both actual process owner tokens. */
public final class MapperConnection {
    public static final int PERMISSION_REQUEST = 1804;
    public interface Result { void onSuccess(String message); void onError(String message); }
    public interface Listener { void onConnectionChanged(); }
    public interface SnapshotCallback { void onSnapshot(Snapshot snapshot); }
    public static final class Snapshot {
        public final String status;
        public final String logs;
        public final boolean running;
        Snapshot(String status, String logs, boolean running) {
            this.status = status; this.logs = logs; this.running = running;
        }
    }

    private static MapperConnection instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
    private final IBinder clientToken = new Binder();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Result> pendingConnections = new ArrayList<>();
    private final Shizuku.UserServiceArgs serviceArgs;
    private volatile IMapperService service;
    private volatile boolean running;
    private volatile boolean busy;
    private volatile long operationGeneration;
    private UserConnection connection;
    private boolean binding;
    private boolean refreshInFlight;
    private String lastStatus = "尚未开始映射";
    private String lastLogs = "按下遥控器后，这里会显示最近的操作。";
    private String terminalStatus;

    public static synchronized MapperConnection get(Context context) {
        if (instance == null) instance = new MapperConnection(context.getApplicationContext());
        return instance;
    }

    private MapperConnection(Context context) {
        this.context = context;
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, MapperUserService.class))
                .processNameSuffix("mapper").daemon(false).debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);
        Shizuku.addBinderReceivedListenerSticky(() -> main.post(() -> {
            if (connection != null && connection.server != Shizuku.getBinder())
                loseConnection("Shizuku 已重新启动，请重新开始映射", true);
            notifyListeners();
        }));
        Shizuku.addBinderDeadListener(() -> main.post(() -> {
            // Do not let a queued death event from the old server invalidate a new binding.
            if (!isShizukuReady()) loseConnection("Shizuku 已停止，请重新启动后再开始映射", true);
        }));
        Shizuku.addRequestPermissionResultListener((code, grant) -> {
            if (code == PERMISSION_REQUEST) main.post(this::notifyListeners);
        });
    }

    private final class UserConnection implements ServiceConnection {
        final IBinder server;
        UserConnection(IBinder server) { this.server = server; }

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            main.post(() -> {
                if (connection != this || server != Shizuku.getBinder() || !isShizukuReady()) return;
                binding = false;
                service = IMapperService.Stub.asInterface(binder);
                finishConnections(true, "控制服务已连接");
                notifyListeners();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            main.post(() -> {
                if (connection != this) return;
                loseConnection(isShizukuReady() ? "控制服务已断开，请重新开始映射"
                        : "Shizuku 已停止，请重新启动后再开始映射", false);
            });
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    }

    private void loseConnection(String reason, boolean destroyOld) {
        IMapperService previous = service;
        UserConnection oldConnection = connection;
        service = null;
        connection = null;
        binding = false;
        operationGeneration++;
        running = false;
        busy = false;
        terminalStatus = reason;
        lastStatus = reason;
        if (oldConnection != null) {
            try { Shizuku.unbindUserService(serviceArgs, oldConnection, false); }
            catch (Throwable ignored) { }
        }
        if (destroyOld && previous != null) cleanup.execute(() -> {
            try { previous.destroy(); } catch (Throwable ignored) { }
        });
        // Cancelled starts also need a result: the foreground service may still be starting.
        finishConnections(false, reason);
        notifyListeners();
    }

    public boolean isShizukuReady() {
        try { return Shizuku.pingBinder(); } catch (Throwable ignored) { return false; }
    }
    public boolean hasPermission() {
        try { return isShizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable ignored) { return false; }
    }
    public boolean isRunning() { return running; }
    public boolean isBusy() { return busy; }
    public IMapperService getService() { return service; }
    public void addListener(Listener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public void requestPermission() { Shizuku.requestPermission(PERMISSION_REQUEST); }

    public void connect(Result result) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> connect(result)); return; }
        if (!isShizukuReady()) { result.onError("请先打开 Shizuku 并启动服务"); return; }
        if (!hasPermission()) { result.onError("请先授权本应用使用 Shizuku"); return; }
        if (service != null && service.asBinder().isBinderAlive() && connection != null
                && connection.server == Shizuku.getBinder()) {
            result.onSuccess("控制服务已连接"); return;
        }
        pendingConnections.add(result);
        if (binding) return;
        service = null;
        binding = true;
        UserConnection attempt = new UserConnection(Shizuku.getBinder());
        connection = attempt;
        try {
            Shizuku.bindUserService(serviceArgs, attempt);
            main.postDelayed(() -> {
                if (binding && connection == attempt)
                    loseConnection("连接超时，请检查 Shizuku 是否正在运行后重试", false);
            }, 15000);
        } catch (Throwable error) {
            loseConnection(friendlyError(error), false);
        }
    }

    public void startMapping(String configJson, Result result) {
        final long generation = ++operationGeneration;
        terminalStatus = null;
        busy = true;
        lastStatus = "正在连接控制服务…";
        notifyListeners();
        connect(new Result() {
            @Override public void onSuccess(String ignored) {
                final IMapperService current = service;
                final IBinder owner = Shizuku.getBinder();
                worker.execute(() -> {
                    if (generation != operationGeneration) {
                        main.post(() -> result.onError("启动已取消")); return;
                    }
                    try {
                        if (current == null || owner == null) throw new IllegalStateException("控制服务已断开");
                        String path = new File(context.getApplicationInfo().nativeLibraryDir, "libble_m3_capture.so").getAbsolutePath();
                        String response = current.startMapping(path, configJson, clientToken, owner);
                        JSONObject startResult = new JSONObject(response);
                        String status = current.getStatus();
                        boolean started = parseRunning(status, false);
                        main.post(() -> {
                            if (generation != operationGeneration || current != service) {
                                result.onError("启动已取消"); return;
                            }
                            busy = false;
                            running = started;
                            lastStatus = status;
                            if (!startResult.optBoolean("ok", false)) {
                                reportFailure(startResult.optString("error", "无法开始映射，请重试"));
                                result.onError(lastStatus);
                            } else if (started) result.onSuccess(response);
                            else {
                                reportFailure(statusMessage(status));
                                result.onError(lastStatus);
                            }
                            notifyListeners();
                        });
                    } catch (Throwable error) {
                        main.post(() -> {
                            if (generation != operationGeneration || current != service) {
                                result.onError("启动已取消"); return;
                            }
                            reportFailure(friendlyError(error));
                            result.onError(lastStatus);
                        });
                    }
                });
            }
            @Override public void onError(String message) {
                if (generation == operationGeneration) reportFailure(message);
                result.onError(message);
            }
        });
    }

    public void reportFailure(String message) {
        terminalStatus = message;
        lastStatus = message;
        running = false;
        busy = false;
        notifyListeners();
    }

    public void stopMapping(Result result) { stopMapping(result, false); }
    public void stopMappingKeepingStatus(Result result) { stopMapping(result, true); }
    private void stopMapping(Result result, boolean preserveStatus) {
        final long generation = ++operationGeneration;
        final IMapperService current = service;
        final String retained = preserveStatus ? terminalStatus : null;
        if (!preserveStatus) terminalStatus = null;
        busy = true;
        finishConnections(false, "启动已取消");
        notifyListeners();
        worker.execute(() -> {
            String errorMessage = null;
            try { if (current != null) current.stopMapping(); }
            catch (Throwable error) { errorMessage = friendlyError(error); }
            final String failure = errorMessage;
            main.post(() -> {
                if (generation != operationGeneration) { result.onError("停止请求已被新的操作取代"); return; }
                busy = false;
                running = false;
                if (retained != null) {
                    terminalStatus = retained;
                    lastStatus = retained;
                } else if (failure != null) {
                    reportFailure(failure);
                } else lastStatus = "映射已停止，遥控器已恢复原有操作";
                if (failure == null || retained != null) result.onSuccess(lastStatus);
                else result.onError(lastStatus);
                notifyListeners();
            });
        });
    }

    public void refresh(SnapshotCallback callback) {
        if (service != null && (!isShizukuReady() || !hasPermission())) {
            loseConnection(!isShizukuReady() ? "Shizuku 已停止，请重新启动后再开始映射"
                    : "Shizuku 授权已失效，请重新授权", true);
        }
        if (refreshInFlight || service == null) { callback.onSnapshot(snapshot()); return; }
        refreshInFlight = true;
        final long generation = operationGeneration;
        final IMapperService current = service;
        worker.execute(() -> {
            String status;
            String logs = null;
            boolean active;
            try {
                status = current.getStatus();
                logs = current.getLogs();
                active = parseRunning(status, false);
            } catch (Throwable error) {
                status = friendlyError(error);
                active = false;
            }
            final String readStatus = status;
            final String readLogs = logs;
            final boolean readRunning = active;
            main.post(() -> {
                refreshInFlight = false;
                if (generation == operationGeneration && current == service) {
                    boolean stoppedUnexpectedly = running && !readRunning && !busy;
                    if (readLogs != null) lastLogs = readLogs;
                    if (stoppedUnexpectedly) terminalStatus = statusMessage(readStatus);
                    lastStatus = terminalStatus != null && !readRunning ? terminalStatus : readStatus;
                    running = readRunning;
                }
                callback.onSnapshot(snapshot());
            });
        });
    }

    private Snapshot snapshot() { return new Snapshot(lastStatus, lastLogs, running); }
    private static boolean parseRunning(String status, boolean fallback) {
        try { return new JSONObject(status).optBoolean("running", fallback); }
        catch (Exception ignored) { return fallback; }
    }
    public static String statusMessage(String status) {
        try {
            JSONObject json = new JSONObject(status);
            String message = json.optString("message", "");
            if (!message.isEmpty()) return message;
            return json.optBoolean("running", false) ? "映射正在运行" : "映射已停止";
        } catch (Exception ignored) { return status == null ? "尚未开始映射" : status; }
    }
    private static String friendlyError(Throwable error) {
        if (error instanceof SecurityException) return "权限不足，请在 Shizuku 中重新授权本应用";
        if (error instanceof android.os.DeadObjectException) return "控制服务已断开，请重新开始映射";
        return "操作未完成，请确认 Shizuku 正在运行，再重试";
    }
    private void finishConnections(boolean success, String message) {
        List<Result> callbacks = new ArrayList<>(pendingConnections);
        pendingConnections.clear();
        for (Result result : callbacks) {
            if (success) result.onSuccess(message); else result.onError(message);
        }
    }
    private void notifyListeners() {
        for (Listener listener : new ArrayList<>(listeners)) listener.onConnectionChanged();
    }
}
