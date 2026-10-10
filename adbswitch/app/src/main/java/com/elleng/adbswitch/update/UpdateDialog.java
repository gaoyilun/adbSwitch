package com.elleng.adbswitch.update;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.DialogInterface;
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

    private UpdateDialog() {
    }

    public static void show(final Context ctx, final UpdateInfo info, final Listener listener) {
        if (info == null || !info.hasUpdate) {
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
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
                    startDownload(ctx, info, dlg, notes, progressBar, progressText, main);
                });
            }
        });
        dlg.setCancelable(!info.mandatory);
        dlg.show();
    }

    /** 下载 → 校验 → 拉起安装器。全程在后台线程，UI 更新切回主线程。 */
    private static void startDownload(final Context ctx, final UpdateInfo info,
                                      final AlertDialog dlg, final View notes,
                                      final ProgressBar bar, final TextView progressText,
                                      final Handler main) {
        final Button positive = dlg.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setEnabled(false);
        }
        notes.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        progressText.setVisibility(View.VISIBLE);
        progressText.setText(R.string.update_preparing);

        new Thread(() -> {
            String failure = null;
            boolean installed = false;
            try {
                final File apk = UpdateDownloader.download(ctx, info.apkUrl, info.sha256, p -> {
                    final int pct = (int) (p.fraction() * 100);
                    main.post(() -> {
                        bar.setIndeterminate(false);
                        bar.setProgress(pct);
                        if (p.totalBytes > 0) {
                            progressText.setText(ctx.getString(R.string.update_progress,
                                    formatSize(p.downloadedBytes), formatSize(p.totalBytes),
                                    formatSize(p.speedBytesPerSec)));
                        } else {
                            progressText.setText(ctx.getString(R.string.update_downloading));
                        }
                    });
                });
                installed = UpdateDownloader.install(ctx, apk);
            } catch (Throwable t) {
                Log.w(TAG, "更新下载/安装失败", t);
                failure = String.valueOf(t.getMessage());
            }
            final String finalFailure = failure;
            final boolean finalInstalled = installed;
            main.post(() -> {
                if (finalInstalled) {
                    dlg.dismiss();
                    return;
                }
                bar.setVisibility(View.GONE);
                progressText.setVisibility(View.VISIBLE);
                progressText.setText(finalFailure == null
                        ? ctx.getString(R.string.update_need_install_permission)
                        : ctx.getString(R.string.update_download_failed));
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
