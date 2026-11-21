package com.elleng.adbswitch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.MessageQueue;
import android.widget.Toast;

import androidx.preference.Preference;

import com.elleng.adbswitch.update.UpdateChecker;
import com.elleng.adbswitch.update.UpdateDialog;
import com.elleng.adbswitch.update.UpdateInfo;

/**
 * 在线更新检查（自动 + 手动）与「关于」行摘要刷新。
 *
 * 从 MainActivity 外提：网络检查、节流、弹窗入口自成一体，
 * 与主界面仅通过「关于」行 Preference 和 Activity 生命周期交互。
 * 后台线程一律只用 application context，不持 Activity 强引用跑网络；
 * 回主线程的 UI 操作前检查 isDestroyed。
 */
final class UpdateCheckDelegate {

    private final Activity activity;
    /** 更新检查进行中：自动/手动共用，防止连点「检查更新」拉起多条检查线程、叠出多个更新弹窗。 */
    private volatile boolean inFlight = false;
    /** 主页「关于」行（initPrefs 里绑定；未绑定时摘要刷新静默跳过） */
    private Preference aboutPref;

    UpdateCheckDelegate(Activity activity) {
        this.activity = activity;
    }

    void bindAboutPref(Preference pref) {
        this.aboutPref = pref;
    }

    /**
     * 启动后自动检测更新（3 天节流，最低优先级）。
     *
     * 触发时机：主线程空闲时（{@link MessageQueue#IdleHandler}）才真正发起，不阻塞启动与主要功能；
     * 磁贴 / 桌面快捷方式开关走独立路径、不打开本 Activity，因此天然不会触发本检查。
     *
     * 弱提示策略（对开关类 App 不友好的是强弹窗，这里改成）：
     *   发现新版本 → 仅一次性 Toast（按版本号去重，不重复打扰）+ 把远端版本记下来，
     *   主页「关于」行随即显示「可更新」；绝不强弹 UpdateDialog。
     * 真正的新版下载入口只在「关于」弹窗的手动「检查更新」里。
     *
     * 当前 GitHub 主通道硬编码激活；未达节流间隔或网络失败时不发请求。
     * 启用后的行为：成功拿到结果才写节流时间戳，网络失败不写，避免弱网用户被节流 3 天。
     */
    void checkOnStart() {
        if (!UpdateChecker.isActive() || !UpdateChecker.shouldCheck(activity)) {
            return;
        }
        // 主线程空闲后再跑：不抢启动与主要功能，符合「优先级最低」的设定
        Looper.getMainLooper().getQueue().addIdleHandler(new MessageQueue.IdleHandler() {
            @Override
            public boolean queueIdle() {
                runAutoCheck();
                return false; // 一次性，不常驻
            }
        });
    }

    private void runAutoCheck() {
        if (inFlight) {
            return;
        }
        inFlight = true;
        // 后台线程只用 application context：网络检查最长十几秒，匿名线程持有 Activity
        // 强引用期间（旋转屏幕/退出）实例无法回收（历史教训：弱化一切跨线程的 Activity 引用）。
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            try {
                UpdateInfo info = null;
                try {
                    info = UpdateChecker.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
                } catch (Throwable t) {
                    android.util.Log.w("AdbSwitch", "update check failed", t);
                }
                if (info == null) {
                    return; // 所有通道失败：不写节流，下次启动重试
                }
                UpdateChecker.markChecked(app);
                if (info.hasUpdate) {
                    // 记下远端版本，关于行离线也能显示「可更新」
                    UpdateChecker.saveLatestKnown(app, info.versionCode, info.versionName);
                    // 仅对「未提示过的新版本」弹一次 Toast，不弹窗、不打扰
                    if (info.versionCode != UpdateChecker.toastedVersion(app)) {
                        final String name = info.versionName;
                        activity.runOnUiThread(() -> Toast.makeText(app,
                                activity.getString(R.string.update_toast_new, name), Toast.LENGTH_LONG).show());
                        UpdateChecker.markToasted(app, info.versionCode);
                    }
                }
                // 无论是否有更新，都刷新关于行（可能刚记下了可更新版本）
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) {
                        return;
                    }
                    refreshAboutSummary();
                });
            } finally {
                inFlight = false;
            }
        }, "update-check").start();
    }

    /**
     * 刷新主页「关于」行的摘要：默认显示当前版本号（如 1.0.0）；
     * 存在已知新版本时改为「1.0.0 可更新1.0.1」，离线也可见。
     */
    void refreshAboutSummary() {
        if (aboutPref == null) {
            return;
        }
        String cur = BuildConfig.VERSION_NAME;
        if (UpdateChecker.hasKnownUpdate(activity, BuildConfig.VERSION_CODE)) {
            String latest = UpdateChecker.getLatestVersionName(activity);
            if (latest == null || latest.trim().isEmpty()) {
                latest = String.valueOf(UpdateChecker.getLatestVersionCode(activity));
            }
            aboutPref.setSummary(activity.getString(R.string.about_summary_update, cur, latest));
        } else {
            aboutPref.setSummary(cur);
        }
    }

    /** 「关于」弹窗里的手动检查入口（功能未激活时按钮仍可见，点按提示尚未配置）。 */
    void manualCheck() {
        if (!UpdateChecker.isActive()) {
            Toast.makeText(activity, R.string.update_not_configured, Toast.LENGTH_SHORT).show();
            return;
        }
        if (inFlight) {
            return; // 上一次检查还在跑：不叠加线程与弹窗
        }
        inFlight = true;
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        // 后台线程只用 application context（同 runAutoCheck：不持 Activity 强引用跑网络）
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            try {
                UpdateInfo info = null;
                try {
                    info = UpdateChecker.check(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
                } catch (Throwable t) {
                    android.util.Log.w("AdbSwitch", "update check failed", t);
                }
                if (info != null) {
                    UpdateChecker.markChecked(app);
                    if (info.hasUpdate) {
                        // 记下远端版本，关于行同步显示「可更新」
                        UpdateChecker.saveLatestKnown(app, info.versionCode, info.versionName);
                    }
                }
                final UpdateInfo result = info;
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) {
                        return;
                    }
                    if (result == null) {
                        Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                    } else if (!result.hasUpdate) {
                        Toast.makeText(activity, R.string.update_already_latest, Toast.LENGTH_SHORT).show();
                    } else {
                        UpdateDialog.show(activity, result, new UpdateDialog.Listener() {
                            @Override
                            public void onOpenUrl(String url) {
                                openExternalUrl(url);
                            }
                        });
                    }
                });
            } finally {
                inFlight = false;
            }
        }, "update-check-manual").start();
    }

    void openExternalUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Throwable t) {
            android.util.Log.w("AdbSwitch", "open url failed: " + url, t);
            Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
        }
    }
}