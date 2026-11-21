package com.elleng.adbswitch.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 更新 APK 的下载与安装拉起。
 *
 * 下载：HttpURLConnection + 强制 SHA-256 校验 + 200MB 体积上限 + 进程级互斥。
 * 安装：FileProvider + ACTION_INSTALL_PACKAGE（缺「安装未知应用」授权时先跳系统设置页）。
 *
 * 为什么不用 DownloadManager：需要边下边显示进度、且要在失败时把半截文件清掉。
 * 为什么写入 getExternalFilesDir：应用专属外部目录，无需任何存储权限；
 * 且与 res/xml/file_paths.xml 的 external-files-path 严格对应。
 * 为什么不做断点续传：SHA-256 是强制的，而续传时前段数据没参与摘要、哈希必然对不上；
 * APK 只有几 MB，重下成本可忽略，不值得为续传放弃完整性校验。
 */
public final class UpdateDownloader {

    private static final String TAG = "UpdateDownloader";
    private static final String FILE_NAME = "update.apk";
    private static final String AUTHORITY_SUFFIX = ".fileprovider";
    private static final int TIMEOUT = 15000;
    /** 进度回调节流：200ms 一次，否则每收到一个包就 post 一次主线程，列表会卡。 */
    private static final long PROGRESS_INTERVAL_MS = 200L;
    /** 下载体积上限：正常 APK 只有几 MB，远超此值说明响应异常（错误页/被劫持），及时止损。 */
    private static final long MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024;
    /** 进程级互斥：同一时刻只允许一个下载，防止并发写同一个 .part 互相损坏。 */
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

    public static final class Progress {
        public final long downloadedBytes;
        public final long totalBytes;
        public final long speedBytesPerSec;

        Progress(long downloadedBytes, long totalBytes, long speedBytesPerSec) {
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.speedBytesPerSec = speedBytesPerSec;
        }

        /** 0f..1f；总长未知时给 0f，由调用方用不确定样式进度条。 */
        public float fraction() {
            return totalBytes <= 0 ? 0f : Math.min(1f, (float) downloadedBytes / (float) totalBytes);
        }
    }

    public interface ProgressListener {
        void onProgress(Progress p);
    }

    private UpdateDownloader() {
    }

    /** 下载目录：优先应用专属外部 Download 目录，未挂载时退回内部缓存。 */
    public static File targetDir(Context ctx) {
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return dir != null ? dir : new File(ctx.getCacheDir(), "download");
    }

    public static File downloadedFile(Context ctx) {
        return new File(targetDir(ctx), FILE_NAME);
    }

    /**
     * 下载 APK（阻塞，必须在后台线程调用）。
     *
     * @param expectedSha256 期望的 SHA-256（小写十六进制），**必填**：
     *                       缺失时直接拒绝下载——没有完整性依据的包绝不落盘、绝不安装。
     * @return 下载完成的文件
     */
    public static File download(Context ctx, String url, String expectedSha256,
                                ProgressListener listener) throws Exception {
        if (expectedSha256 == null || expectedSha256.trim().isEmpty()) {
            throw new IllegalStateException("缺少 SHA-256，拒绝下载");
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有下载进行中");
        }
        try {
            return downloadLocked(ctx, url, expectedSha256, listener);
        } finally {
            DOWNLOADING.set(false);
        }
    }

    private static File downloadLocked(Context ctx, String url, String expectedSha256,
                                       ProgressListener listener) throws Exception {
        File dir = targetDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建下载目录: " + dir);
        }
        File part = new File(dir, FILE_NAME + ".part");
        File dest = downloadedFile(ctx);
        // 不做续传：残留的半截文件一律丢弃重下（截断写入即使 delete 失败也能丢弃旧内容）
        if (part.exists() && !part.delete()) {
            Log.w(TAG, "删除残留 .part 失败（改用截断写入）: " + part);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }
            long total = conn.getContentLengthLong();
            if (total > MAX_DOWNLOAD_BYTES) {
                throw new IllegalStateException("响应体积异常: " + total + " 字节");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long downloaded = 0L;
            long lastReport = 0L;
            long lastBytes = 0L;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    downloaded += n;
                    if (downloaded > MAX_DOWNLOAD_BYTES) {
                        throw new IllegalStateException("下载体积超过上限 " + MAX_DOWNLOAD_BYTES + " 字节");
                    }
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    long now = System.currentTimeMillis();
                    if (listener != null && now - lastReport >= PROGRESS_INTERVAL_MS) {
                        long speed = (now - lastReport) > 0
                                ? (downloaded - lastBytes) * 1000 / (now - lastReport) : 0;
                        listener.onProgress(new Progress(downloaded, total, speed));
                        lastReport = now;
                        lastBytes = downloaded;
                    }
                }
            }
            if (downloaded <= 0L) {
                throw new IllegalStateException("下载内容为空");
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            if (!sb.toString().equalsIgnoreCase(expectedSha256)) {
                // 源被污染或下载损坏，绝不安装
                deleteQuietly(part);
                deleteQuietly(dest);
                throw new IllegalStateException("APK SHA-256 校验失败：期望 " + expectedSha256
                        + "，实际 " + sb);
            }
            // 先删掉旧的再 rename，否则 rename 可能失败
            deleteQuietly(dest);
            if (!part.renameTo(dest)) {
                throw new IllegalStateException("下载完成但重命名失败: " + part);
            }
            if (listener != null) {
                listener.onProgress(new Progress(downloaded, downloaded, 0));
            }
            return dest;
        } catch (Throwable t) {
            // 失败时清掉半截文件，避免残留
            deleteQuietly(part);
            throw (t instanceof Exception) ? (Exception) t : new IllegalStateException(String.valueOf(t));
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 拉起系统安装器。
     *
     * @return true 已成功拉起；false 表示缺少「安装未知应用」授权（已跳系统设置页，需用户授权后重试）
     */
    public static boolean install(Context ctx, File file) {
        // minSdk=27，canRequestPackageInstalls 必然可用，无需 SDK_INT 判断
        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
            Log.w(TAG, "缺少安装未知应用授权，跳转设置页");
            try {
                ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                Log.w(TAG, "跳转安装授权页失败", t);
            }
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + AUTHORITY_SUFFIX, file);
            ctx.startActivity(new Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "拉起安装失败", t);
            return false;
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            Log.w(TAG, "删除失败: " + f);
        }
    }

    /** 供外部确认文件确实存在且非空（安装前的一道弱校验）。 */
    public static boolean looksValid(File f) {
        try {
            return f != null && f.isFile() && f.length() > 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 校验已下载文件的整体 SHA-256 是否与期望一致（阻塞读全文件；2MB 级 APK 耗时可忽略）。
     * 用于「复用上次已下载但没装上的 APK」前的完整性确认，防止把陈旧/损坏的旧包装出去。
     * 期望值为 null 时返回 false —— 没法确认是哪次的包，宁可不复用（调用方应回退重新下载）。
     */
    public static boolean sha256Matches(File f, String expectedSha256) {
        if (f == null || !f.isFile() || expectedSha256 == null) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().equalsIgnoreCase(expectedSha256);
        } catch (Throwable t) {
            Log.w(TAG, "SHA-256 校验异常: " + f, t);
            return false;
        }
    }
}
