package com.elleng.adbswitch.update;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.elleng.adbswitch.R;

import java.io.File;
import java.util.Locale;

/**
 * 「发现新版本」弹窗。
 *
 * 用 framework AlertDialog（本工程是 framework PreferenceActivity，不引入 Compose/MIUIX）：
 * 标题=发现新版本，正文=更新说明，按钮=立刻更新 /（无忽略）/ 备用下载。
 * 点「立刻更新」后弹窗不消失，转为显示下载进度；下载完成后拉起系统安装器。
 *
 * 「安装未知应用」授权闭环：下载完成后若缺该授权，{@link UpdateDownloader#install}
 * 会跳系统设置页并返回 false，弹窗停在「等待授权」状态并记住会话；用户授权后返回，
 * 宿主在 onResume 里调 {@link #onHostResumed(Context)}，已授权则直接复用磁盘上
 * sha256 校验通过的 APK 拉起安装（{@link #startDownload} 内部命中缓存即跳过下载，
 * 绝不重新下载）。点「重试」走同一条复用路径，结果一致。
 *
 * 不提供「忽略」按钮：本 App 是弱提示路线，发现新版要么更新、要么直接关掉弹窗，
 * 不需要「永久别再提醒」的状态——省掉这个出口，也省掉一整套忽略持久化逻辑。
 *
 * 强制更新（mandatory）：不允许点外部/返回关闭。
 *
 * 当前 {@link UpdateConfig#ENABLED} 为 false，本类不会被调用。
 */
public final class UpdateDialog {

    private static final String TAG = "UpdateDialog";

    /** 主通道失败/用户想换渠道时的回调（交由外部打开浏览器）。 */
    public interface Listener {
        void onOpenUrl(String url);
    }

    /**
     * 一次「下载→安装」会话的 UI 与状态。静态持有是因为宿主 onResume 需要能找到
     * 当前会话（从「安装未知应用」授权页回来时自动续装）；弹窗 dismiss 或宿主重建时
     * 置空，不长期持有 Activity。
     */
    private static final class Session {
        final Context host;
        final UpdateInfo info;
        final AlertDialog dlg;
        final View notes;
        final ProgressBar bar;
        final TextView progressText;
        final Handler main = new Handler(Looper.getMainLooper());
        /** install() 因缺「安装未知应用」授权返回 false 后置 true，授权回来自动续装。 */
        boolean awaitingInstallPermission;

        Session(Context host, UpdateInfo info, AlertDialog dlg,
                View notes, ProgressBar bar, TextView progressText) {
            this.host = host;
            this.info = info;
            this.dlg = dlg;
            this.notes = notes;
            this.bar = bar;
            this.progressText = progressText;
        }
    }

    private static Session sSession;

    private UpdateDialog() {
    }

    public static void show(final Context ctx, final UpdateInfo info, final Listener listener) {
        if (info == null || !info.hasUpdate) {
            return;
        }
        View view = LayoutInflater.from(ctx).inflate(R.layout.dialog_update, null);
        final TextView notes = view.findViewById(R.id.tv_notes);
        final TextView progressText = view.findViewById(R.id.tv_progress);
        final ProgressBar progressBar = view.findViewById(R.id.pb_progress);

        if (info.notes.trim().isEmpty()) {
            notes.setVisibility(View.GONE);
        } else {
            notes.setText(info.notes.trim());
        }

        AlertDialog.Builder b = new MaterialAlertDialogBuilder(ctx)
                .setTitle(ctx.getString(R.string.update_found, info.versionName))
                .setView(view)
                // 按钮的点击逻辑在 onShow 里接管，否则点了就自动 dismiss，来不及显示下载进度
                .setPositiveButton(R.string.update_now, null)
                .setNeutralButton(R.string.update_backup, null);
        final AlertDialog dlg = b.create();

        final Session s = new Session(ctx, info, dlg, notes, progressBar, progressText);

        dlg.setOnDismissListener(d -> {
            // 会话随弹窗结束，避免静态字段一直持有 Activity
            if (sSession == s) {
                sSession = null;
            }
        });

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                final Button positive = dlg.getButton(DialogInterface.BUTTON_POSITIVE);
                final Button neutral = dlg.getButton(DialogInterface.BUTTON_NEUTRAL);

                if (neutral != null) {
                    if (info.fallbackUrl == null) {
                        neutral.setVisibility(View.GONE);
                    } else {
                        neutral.setOnClickListener(v -> {
                            if (listener != null) {
                                listener.onOpenUrl(info.fallbackUrl);
                            }
                            dlg.dismiss();
                        });
                    }
                }
                // 蒲公英通道没有 APK 直链（apkUrl 为 null），此时「立刻更新」退化为打开安装页
                final boolean directDownload = info.apkUrl != null;
                if (!directDownload) {
                    positive.setText(ctx.getString(R.string.update_open_page));
                }
                positive.setOnClickListener(v -> {
                    if (!directDownload) {
                        if (listener != null) {
                            listener.onOpenUrl(info.fallbackUrl != null
                                    ? info.fallbackUrl : UpdateConfig.PGYER_PAGE_URL);
                        }
                        dlg.dismiss();
                        return;
                    }
                    startDownload(s);
                });
            }
        });
        dlg.setCancelable(!info.mandatory);
        dlg.show();
        sSession = s;
    }

    /**
     * 宿主 onResume 回调：弹窗正停在「等待安装授权」状态、且用户刚在系统设置里完成
     * 「安装未知应用」授权返回时，直接复用已下载的 APK 继续安装（内部命中缓存，
     * 不会重新下载）。未授权就返回则什么都不做，保持原状等用户手动「重试」。
     */
    public static void onHostResumed(Context host) {
        Session s = sSession;
        if (s == null || !s.awaitingInstallPermission
                || s.dlg == null || !s.dlg.isShowing()) {
            return;
        }
        if (s.host != host) {
            // 宿主已重建（配置变更/进程回收后恢复）：旧弹窗已随旧实例销毁，会话作废
            sSession = null;
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !host.getPackageManager().canRequestPackageInstalls()) {
            return; // 仍未授权：保持「等待授权」状态
        }
        s.awaitingInstallPermission = false;
        startDownload(s);
    }

    /**
     * 下载 → 校验 → 拉起安装器。全程在后台线程，UI 更新切回主线程。
     * 磁盘上已有 sha256 校验通过的完整 APK 时直接复用（「等待授权」回来续装、
     * 或下载后中途返回再点「立刻更新」都不重新下载）。
     */
    private static void startDownload(final Session s) {
        final Context ctx = s.host;
        final UpdateInfo info = s.info;
        final AlertDialog dlg = s.dlg;
        final Button positive = dlg.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setEnabled(false);
        }
        s.notes.setVisibility(View.GONE);
        s.bar.setVisibility(View.VISIBLE);
        s.bar.setIndeterminate(true);
        s.progressText.setVisibility(View.VISIBLE);
        s.progressText.setText(R.string.update_preparing);

        new Thread(() -> {
            String failure = null;
            boolean installed = false;
            try {
                File apk;
                File cached = UpdateDownloader.downloadedFile(ctx);
                if (info.sha256 != null && UpdateDownloader.looksValid(cached)
                        && UpdateDownloader.sha256Matches(cached, info.sha256)) {
                    // 之前下载完成但没装上（缺授权/中途返回）：校验通过就直接复用
                    Log.i(TAG, "复用已下载且校验通过的 APK: " + cached);
                    apk = cached;
                } else {
                    apk = UpdateDownloader.download(ctx, info.apkUrl, info.sha256, p -> {
                        final int pct = (int) (p.fraction() * 100);
                        s.main.post(() -> {
                            s.bar.setIndeterminate(false);
                            s.bar.setProgress(pct);
                            if (p.totalBytes > 0) {
                                s.progressText.setText(ctx.getString(R.string.update_progress,
                                        formatSize(p.downloadedBytes), formatSize(p.totalBytes),
                                        formatSize(p.speedBytesPerSec)));
                            } else {
                                s.progressText.setText(ctx.getString(R.string.update_downloading));
                            }
                        });
                    });
                }
                installed = UpdateDownloader.install(ctx, apk);
            } catch (Throwable t) {
                Log.w(TAG, "更新下载/安装失败", t);
                failure = String.valueOf(t.getMessage());
            }
            final String finalFailure = failure;
            final boolean finalInstalled = installed;
            s.main.post(() -> {
                if (finalInstalled) {
                    dlg.dismiss();
                    return;
                }
                s.bar.setVisibility(View.GONE);
                s.progressText.setVisibility(View.VISIBLE);
                if (finalFailure == null) {
                    // install() 返回 false：缺「安装未知应用」授权时它已跳系统设置页。
                    // 记住等待状态，授权回来由 onHostResumed 自动续装；若权限其实已授予
                    // （如设备上没有安装器），按失败处理，避免 onHostResumed 无意义自旋。
                    boolean missingPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && !ctx.getPackageManager().canRequestPackageInstalls();
                    s.awaitingInstallPermission = missingPerm;
                    s.progressText.setText(ctx.getString(missingPerm
                            ? R.string.update_need_install_permission
                            : R.string.update_download_failed));
                } else {
                    s.progressText.setText(R.string.update_download_failed);
                }
                if (positive != null) {
                    positive.setEnabled(true);
                    positive.setText(R.string.update_retry);
                }
            });
        }, "update-download").start();
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%d KB", bytes / 1024);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
