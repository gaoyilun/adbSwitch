package com.elleng.adbswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台服务：调试被临时关闭后，在通知栏常驻一条「当前已关闭」通知，
 * 并提供「恢复打开」一键动作。
 *
 * 存续条件只有一个：USB 与无线**两个都关**时才常驻；任一被打开就自动撤掉。
 * 状态一律从 {@link DebugController} 读，本服务不自己判断要不要管无线调试。
 */
public class AdbToggleService extends Service {

    private static final String TAG = "AdbToggleService";

    public static final String CHANNEL_ID = "adb_switch_channel";
    public static final int NOTIFY_ID = 1;

    public static final String ACTION_OPEN = "com.elleng.adbswitch.ACTION_OPEN";
    public static final String ACTION_CLOSE = "com.elleng.adbswitch.ACTION_CLOSE";
    public static final String ACTION_SETTINGS = "com.elleng.adbswitch.ACTION_SETTINGS";
    /** Live Update「取消置顶」：撤掉常驻通知（不改 adb_enabled 真值） */
    public static final String ACTION_DISMISS = "com.elleng.adbswitch.ACTION_DISMISS";
    /** 启动时由调用方告知「当前应为关闭态」，避免在服务里做阻塞 su 读状态导致通知延迟 */
    public static final String EXTRA_STATE_ON = "state_on";

    private NotificationManager nm;
    private boolean observerRegistered = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (DebugController.ACTION_STATE.equals(i.getAction())) {
                DebugController.State s = new DebugController.State(
                        i.getBooleanExtra(DebugController.EXTRA_USB, false),
                        i.getBooleanExtra(DebugController.EXTRA_WIRELESS, false),
                        i.getBooleanExtra(DebugController.EXTRA_DEV, false));
                if (s.anyOn()) {
                    // 任一被打开：adbd 又跑起来了，常驻通知没有意义，撤掉
                    ToggleShortcuts.publish(AdbToggleService.this);
                    stopSelf();
                } else {
                    // 实时刷新为「已关闭」状态
                    updateNotification(s);
                }
            }
        }
    };

    /**
     * 外部改动感知：常驻通知存续的唯一条件是「两个都关」。
     * 但调试状态可能被本 App 之外的东西打开（系统开发者选项、adb、别的 root 工具），
     * 那时内部广播不会发，通知会停在「已关闭」骗人——与 App 的确认真实状态使命冲突。
     * 故服务存续期间监听 Settings，发现任一开启就自动退场。
     */
    // 注意：字段初始化在 Service 构造函数阶段执行，此时 attachBaseContext 还没发生，
    // 绝不能用实例的 getMainLooper()（会 NPE 且直接崩掉服务），必须用静态 Looper。
    private final android.database.ContentObserver settingsObserver =
            new android.database.ContentObserver(
                    new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    // 只做一件事：作废缓存并后台重新校准。
                    // 校准完成后会广播，stateReceiver 才是判断「该不该退场」的唯一地方 ——
                    // 在这里同步 read() 拿到的是作废前的旧缓存（服务存续期间必然是「两个都关」），
                    // 判断永远为假，等于白判。
                    DebugController.invalidateAndRefresh(AdbToggleService.this);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        IntentFilter f = new IntentFilter(DebugController.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
        try {
            // 只监听这两个键：监听整个 Settings.Global.CONTENT_URI 会让任何 global 变更都回调一次
            for (String key : new String[]{
                    DebugController.KEY_USB, DebugController.KEY_WIRELESS}) {
                getContentResolver().registerContentObserver(
                        android.provider.Settings.Global.getUriFor(key), false, settingsObserver);
            }
            observerRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "register settings observer failed", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 优先用调用方传入的已知聚合态；只有缺省时才回读（su 是阻塞调用，会拖慢前台服务起通知）。
        DebugController.State s;
        if (intent != null && intent.hasExtra(EXTRA_STATE_ON)) {
            boolean anyOn = intent.getBooleanExtra(EXTRA_STATE_ON, false);
            // dev 只是随行字段，这里用调用方已知的聚合态构造，dev 取 false（通知不展示它）
            s = anyOn ? new DebugController.State(true, false, false)
                    : new DebugController.State(false, false, false);
        } else {
            s = DebugController.read(this);
        }
        if (s.anyOn()) {
            // 任一还开着：不需要常驻通知。
            // 但本服务可能经 startForegroundService() 拉起，必须先 startForeground 再停，
            // 否则系统按「没在时限内进前台」抛 ForegroundServiceDidNotStartInTimeException。
            // startForeground 后立即 stopSelf，onDestroy 会把这条占位通知取消掉。
            startForegroundCompat(buildNotification(this, s));
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat(buildNotification(this, s));
        return START_NOT_STICKY;
    }

    /**
     * Android 14（API 34）起前台服务强制校验类型：manifest 声明了 specialUse，
     * 就必须用带 type 的 startForeground 重载，否则会抛异常导致通知不出现。
     */
    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                // 29+ 传 0 表示沿用 manifest 中声明的类型
                startForeground(NOTIFY_ID, notification, 0);
            } else {
                startForeground(NOTIFY_ID, notification);
            }
        } catch (Throwable t) {
            // 极端情况下退回两参重载，至少别让服务崩
            Log.e(TAG, "startForeground(type) failed, fallback", t);
            try {
                startForeground(NOTIFY_ID, notification);
            } catch (Throwable ignored) {
                Log.e(TAG, "startForeground fallback failed", ignored);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {
        }
        if (observerRegistered) {
            try {
                getContentResolver().unregisterContentObserver(settingsObserver);
            } catch (Throwable ignored) {
            }
            observerRegistered = false;
        }
        if (nm != null) {
            nm.cancel(NOTIFY_ID);
        }
    }

    /** 实时刷新通知内容（状态变化或本服务内主动调用） */
    private void updateNotification(DebugController.State s) {
        ToggleShortcuts.publish(this);
        if (nm != null) {
            nm.notify(NOTIFY_ID, buildNotification(this, s));
        }
    }

    /**
     * 关闭调试后的统一收口：按「临时关闭时弹通知」开关决定常驻通知的去留。
     * 主界面 / 磁贴 / 通知按钮都走这里，保证三个入口语义一致。
     * （键名 cfg/notify_on_close 与 MainActivity、prefs.xml 共用同一份配置）
     */
    public static void syncCloseNotification(Context ctx) {
        boolean notifyOnClose = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getBoolean("notify_on_close", true);
        if (notifyOnClose) {
            start(ctx);
        } else {
            stop(ctx);
        }
    }

    /**
     * 开启常驻通知（调用方已先把调试关掉）；显式告知 stateOn=false 以免服务内阻塞读状态。
     *
     * 坑：Android 12+ 禁止从后台启动前台服务。通知按钮 / 磁贴触发时进程在后台，
     * 直接 startForegroundService() 会抛 ForegroundServiceStartNotAllowedException，
     * 把 BroadcastReceiver 整个带崩（状态倒是已经写好了，但通知出不来、还留下崩溃记录）。
     * 故这里捕获该异常并降级：不起服务，直接用 NotificationManager 发同一条常驻通知。
     */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, AdbToggleService.class);
        i.putExtra(EXTRA_STATE_ON, false);
        try {
            ctx.startForegroundService(i);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService not allowed, fallback to direct notify", t);
            postDirect(ctx, DebugController.read(ctx));
        }
    }

    /** 移除常驻通知（恢复正常时调用）：前台服务与降级直发两种方式都要撤掉 */
    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, AdbToggleService.class));
        } catch (Throwable ignored) {
        }
        NotificationManager nmm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nmm != null) {
            nmm.cancel(NOTIFY_ID);
        }
    }

    /** 降级路径：不起前台服务，直接发常驻通知（内容与服务内发的完全一致） */
    private static void postDirect(Context ctx, DebugController.State s) {
        NotificationManager nmm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nmm == null) {
            return;
        }
        ensureChannel(ctx, nmm);
        try {
            nmm.notify(NOTIFY_ID, buildNotification(ctx, s));
        } catch (Throwable t) {
            Log.e(TAG, "postDirect failed", t);
        }
    }

    private void createChannel() {
        ensureChannel(this, nm);
    }

    private static void ensureChannel(Context ctx, NotificationManager nmm) {
        if (Build.VERSION.SDK_INT >= 26) {
            // Live Updates 要求重要性高于 MIN；用 DEFAULT 保证可被系统提升为实时动态，
            // 同时仍属低打扰（无音效、collapse 行为由系统控制）。
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.notify_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(ctx.getString(R.string.notify_channel_desc));
            ch.setShowBadge(false);
            nmm.createNotificationChannel(ch);
        }
    }

    private static Notification buildNotification(Context ctx, DebugController.State s) {
        boolean on = s.anyOn();
        Intent open = new Intent(ctx, NotificationReceiver.class).setAction(ACTION_OPEN);
        PendingIntent piOpen = PendingIntent.getBroadcast(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE);
        Intent close = new Intent(ctx, NotificationReceiver.class).setAction(ACTION_CLOSE);
        PendingIntent piClose = PendingIntent.getBroadcast(ctx, 2, close, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        // 必须设 ongoing：Android 16 的 Live Update 提升（setRequestPromotedOngoing）以
        // 「ongoing 通知」为前提——前台服务用的是 specialUse 类型不被提升，所以不设 ongoing 时
        // 实际常驻通知只显示为普通通知（不进状态栏胶囊/锁屏）。
        b.setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(ctx.getString(on ? R.string.notify_title_on : R.string.notify_title_off))
                .setContentText(describeSubStates(ctx, s))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW);

        // —— Android 16 Live Updates ——
        // 常驻通知请求被系统「提升」为 Live Update：状态栏胶囊 + 锁屏/AOD 常显、不可滑动清除。
        // 用反射调用 setRequestPromotedOngoing / setShortCriticalText：
        // 这两个方法是 Android 16 QPR1 才加入的，编译期 SDK 36 可能仍标 @RequiresApi(36) 而拒绝直接调用，
        // 反射可同时规避「编译不过」与「低版本崩溃」两个问题。不支持的设备自动退化为普通常驻通知。
        try {
            android.app.Notification.Builder.class
                    .getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(b, true);
            // 状态栏胶囊里的短文本（Live Update 才有意义）
            android.app.Notification.Builder.class
                    .getMethod("setShortCriticalText", String.class)
                    .invoke(b, ctx.getString(on ? R.string.promoted_text_on : R.string.promoted_text_off));
        } catch (Throwable t) {
            Log.w(TAG, "promoted ongoing not supported on this API level", t);
        }

        if (on) {
            b.addAction(0, ctx.getString(R.string.notify_action_close), piClose);
        } else {
            b.addAction(0, ctx.getString(R.string.notify_action_open), piOpen);
            // 联动模式下，提供「应用设置」入口，避免开启联动后无法改设置
            Intent settings = new Intent(ctx, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_SETTINGS, true);
            PendingIntent piSettings = PendingIntent.getActivity(ctx, 4, settings, PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0, ctx.getString(R.string.notify_settings), piSettings);
        }

        // 点通知主体打开应用设置页
        Intent main = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_SETTINGS, true);
        PendingIntent piMain = PendingIntent.getActivity(ctx, 3, main, PendingIntent.FLAG_IMMUTABLE);
        b.setContentIntent(piMain);
        return b.build();
    }

    /** 通知副文本：把两个子开关的真值都摊开，一眼看出 adbd 为什么还在跑 */
    private static String describeSubStates(Context ctx, DebugController.State s) {
        String usb = ctx.getString(s.usb ? R.string.notify_sub_usb_on : R.string.notify_sub_usb_off);
        String wifi = ctx.getString(s.wireless
                ? R.string.notify_sub_wifi_on : R.string.notify_sub_wifi_off);
        return usb + " · " + wifi + " · " + ctx.getString(
                s.allOff() ? R.string.notify_sub_safe : R.string.notify_sub_running);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
