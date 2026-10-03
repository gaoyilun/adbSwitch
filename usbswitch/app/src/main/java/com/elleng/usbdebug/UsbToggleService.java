package com.elleng.usbdebug;

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
public class UsbToggleService extends Service {

    private static final String TAG = "UsbToggleService";

    public static final String CHANNEL_ID = "usb_switch_channel";
    public static final int NOTIFY_ID = 1;

    public static final String ACTION_OPEN = "com.elleng.usbdebug.ACTION_OPEN";
    public static final String ACTION_CLOSE = "com.elleng.usbdebug.ACTION_CLOSE";
    public static final String ACTION_SETTINGS = "com.elleng.usbdebug.ACTION_SETTINGS";
    /** Live Update「取消置顶」：撤掉常驻通知（不改 adb_enabled 真值） */
    public static final String ACTION_DISMISS = "com.elleng.usbdebug.ACTION_DISMISS";
    /** 启动时由调用方告知「当前应为关闭态」，避免在服务里做阻塞 su 读状态导致通知延迟 */
    public static final String EXTRA_STATE_ON = "state_on";

    private NotificationManager nm;
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (DebugController.ACTION_STATE.equals(i.getAction())) {
                DebugController.State s = new DebugController.State(
                        i.getBooleanExtra(DebugController.EXTRA_USB, false),
                        i.getBooleanExtra(DebugController.EXTRA_WIRELESS, false));
                if (s.anyOn()) {
                    // 任一被打开：adbd 又跑起来了，常驻通知没有意义，撤掉
                    stopSelf();
                } else {
                    // 实时刷新为「已关闭」状态
                    updateNotification(s);
                }
            }
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 优先用调用方传入的已知聚合态；只有缺省时才回读（su 是阻塞调用，会拖慢前台服务起通知）。
        DebugController.State s;
        if (intent != null && intent.hasExtra(EXTRA_STATE_ON)) {
            boolean anyOn = intent.getBooleanExtra(EXTRA_STATE_ON, false);
            s = anyOn ? new DebugController.State(true, false) : new DebugController.State(false, false);
        } else {
            s = DebugController.read();
        }
        if (s.anyOn()) {
            // 任一还开着：不需要常驻通知
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
        if (nm != null) {
            nm.cancel(NOTIFY_ID);
        }
    }

    /** 实时刷新通知内容（状态变化或本服务内主动调用） */
    private void updateNotification(DebugController.State s) {
        if (nm != null) {
            nm.notify(NOTIFY_ID, buildNotification(this, s));
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
        Intent i = new Intent(ctx, UsbToggleService.class);
        i.putExtra(EXTRA_STATE_ON, false);
        try {
            ctx.startForegroundService(i);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService not allowed, fallback to direct notify", t);
            postDirect(ctx, DebugController.read());
        }
    }

    /** 移除常驻通知（恢复正常时调用）：前台服务与降级直发两种方式都要撤掉 */
    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, UsbToggleService.class));
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
                    CHANNEL_ID, "调试开关", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("调试临时关闭后的常驻控制通知");
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
        // 不设 ongoing：常驻通知需要一直显示到恢复调试，但也要允许手动划掉
        // （setOngoing(true) 会禁止滑动清除，在去掉「取消置顶」按钮后就再也无法关闭了）
        b.setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(on ? "调试：已开启" : "调试：当前已关闭")
                .setContentText(describeSubStates(s))
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
                    .invoke(b, on ? "调试中" : "已关闭");
        } catch (Throwable t) {
            Log.w(TAG, "promoted ongoing not supported on this API level", t);
        }

        if (on) {
            b.addAction(0, "临时关闭", piClose);
        } else {
            b.addAction(0, "恢复打开", piOpen);
            // 联动模式下，提供「应用设置」入口，避免开启联动后无法改设置
            Intent settings = new Intent(ctx, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_SETTINGS, true);
            PendingIntent piSettings = PendingIntent.getActivity(ctx, 4, settings, PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0, ctx.getString(R.string.notify_settings), piSettings);
        }

        // 原先这里的「取消置顶」动作已移除：常驻通知现在可以直接滑动关闭，多一个按钮反而碍事。
        // 点通知主体打开应用设置页
        Intent main = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_SETTINGS, true);
        PendingIntent piMain = PendingIntent.getActivity(ctx, 3, main, PendingIntent.FLAG_IMMUTABLE);
        b.setContentIntent(piMain);
        return b.build();
    }

    /** 通知副文本：把两个子开关的真值都摊开，一眼看出 adbd 为什么还在跑 */
    private static String describeSubStates(DebugController.State s) {
        String usb = s.usb ? "USB 开" : "USB 关";
        String wifi = s.wireless ? "无线 开" : "无线 关";
        if (s.allOff()) {
            return usb + " · " + wifi + " · adbd 已停止，检测可通过";
        }
        return usb + " · " + wifi + " · adbd 仍在运行";
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
