package com.elleng.adbswitch.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 更新 APK 的下载与安装拉起。
 *
 * 下载：HttpURLConnection + Range 断点续传 + 可选 SHA-256 校验。
 * 安装：FileProvider + ACTION_INSTALL_PACKAGE（缺「安装未知应用」授权时先跳系统设置页）。
 *
 * 为什么不用 DownloadManager：需要边下边显示进度、且要在失败时把半截文件清掉。
 * 为什么写入 getExternalFilesDir：应用专属外部目录，无需任何存储权限；
 * 且与 res/xml/file_paths.xml 的 external-files-path 严格对应。
 */
public final class UpdateDownloader {

    private static final String TAG = "UpdateDownloader";
    private static final String FILE_NAME = "update.apk";
    private static final String AUTHORITY_SUFFIX = ".fileprovider";
    private static final int TIMEOUT = 15000;
    /** 进度回调节流：200ms 一次，否则每收到一个包就 post 一次主线程，列表会卡。 */
    private static final long PROGRESS_INTERVAL_MS = 200L;

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
     * @param expectedSha256 期望的 SHA-256（小写十六进制）；null 时跳过校验。
     *                       注意：提供它会禁用断点续传 —— 续传时前段数据没参与摘要，
     *                       算出来的哈希必然对不上（与 LivePhoto 实现保持一致）。
     * @return 下载完成的文件
     */
    public static File download(Context ctx, String url, String expectedSha256,
                                ProgressListener listener) throws Exception {
        File dir = targetDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建下载目录: " + dir);
        }
        File part = new File(dir, FILE_NAME + ".part");
        File dest = downloadedFile(ctx);
        long downloaded = part.exists() ? part.length() : 0L;
        boolean resume = downloaded > 0 && expectedSha256 == null;
        if (!resume && part.exists() && !part.delete()) {
            Log.w(TAG, "删除残留 .part 失败: " + part);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            if (resume) {
                conn.setRequestProperty("Range", "bytes=" + downloaded + "-");
            }
            int code = conn.getResponseCode();
            if (code == 206 && resume) {
                Log.i(TAG, "断点续传，从 " + downloaded + " 字节继续");
            } else if (code == 200) {
                // 服务端不支持续传（或本来就从头下）：丢弃已下载部分
                downloaded = 0L;
            } else {
                throw new IllegalStateException("HTTP " + code);
            }
            long total = downloaded + conn.getContentLengthLong();

            MessageDigest digest = (expectedSha256 == null) ? null
                    : MessageDigest.getInstance("SHA-256");
            long lastReport = 0L;
            long lastBytes = 0L;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(part, resume)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (digest != null) {
                        digest.update(buf, 0, n);
                    }
                    downloaded += n;
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
            if (digest != null) {
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
            }
            // 分次写入的文件要先删掉旧的再 rename，否则 rename 可能失败
            deleteQuietly(dest);
            if (!part.renameTo(dest)) {
                throw new IllegalStateException("下载完成但重命名失败: " + part);
            }
            if (listener != null) {
                listener.onProgress(new Progress(downloaded, downloaded, 0));
            }
            return dest;
        } catch (Throwable t) {
            // 失败时清掉半截文件，否则下次会拿一个残缺的 .part 续传
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
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
}
