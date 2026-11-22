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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 更新 APK 的下载与安装拉起。
 *
 * 下载：多直链并行测速择优 + 掉速/失败自动切源 + 强制 SHA-256 校验 + 200MB 体积上限 + 进程级互斥。
 * 安装：FileProvider + ACTION_INSTALL_PACKAGE（缺「安装未知应用」授权时先跳系统设置页）。
 *
 * 为什么不用 DownloadManager：需要边下边显示进度、且要在失败时把半截文件清掉。
 * 为什么写入 getExternalFilesDir：应用专属外部目录，无需任何存储权限；
 * 且与 res/xml/file_paths.xml 的 external-files-path 严格对应。
 * 为什么不做断点续传：SHA-256 是强制的，而续传时前段数据没参与摘要、哈希必然对不上；
 * APK 只有几 MB，重下成本可忽略，不值得为续传放弃完整性校验。
 *
 * 多源策略（GitHub 资产 + 蒲公英签名直链，两者同版本时各给一条）：
 *  1. 用户点「立刻更新」时才测速（不在检查更新阶段做）：用 Range 请求各拉一小段
 *     （默认 256KB，硬上限 4s）实测吞吐，按速度排序，测速数据丢弃；
 *  2. 按速度顺序下载，**选定之后全程不因速度切换**——慢就是慢，用户的网络环境
 *     不该被我们猜来猜去；只有当前这条**真的失败**（连接错误 / HTTP 非 200 /
 *     体积异常 / SHA-256 不符）才换下一条，此时反正没有可丢的进度；
 *  3. 全部源都失败才抛最后一次的异常。
 *  4. 成功源的主机名记进 update_prefs，下次测速时若它速度不低于最快源的
 *     {@link #PREFERRED_HOST_RATIO} 就继续用它，避免在两个相近速度的源之间来回跳。
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

    /** 测速取样字节数；硬上限内读不完就按已读字节数算速度（慢源本来也读不完）。 */
    private static final int PROBE_BYTES = 256 * 1024;
    /** 单条源测速的总时限：超时即用已读字节判速。 */
    private static final long PROBE_DEADLINE_MS = 4000L;

    /** 上次成功源主机的速度达到最快源的该比例，就继续沿用（减少无谓切换）。 */
    private static final double PREFERRED_HOST_RATIO = 0.75;

    private static final String PREFS = "update_prefs";
    private static final String KEY_BEST_HOST = "best_download_host";

    /** 进程级互斥：同一时刻只允许一个下载，防止并发写同一个 .part 互相损坏。 */
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

    public static final class Progress {
        public final long downloadedBytes;
        public final long totalBytes;
        public final long speedBytesPerSec;
        /** 当前源在候选列表里的序号（从 1 开始），用于「已切换下载源」类提示。 */
        public final int sourceIndex;
        public final int sourceCount;

        Progress(long downloadedBytes, long totalBytes, long speedBytesPerSec,
                 int sourceIndex, int sourceCount) {
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.speedBytesPerSec = speedBytesPerSec;
            this.sourceIndex = sourceIndex;
            this.sourceCount = sourceCount;
        }

        /** 0f..1f；总长未知时给 0f，由调用方用不确定样式进度条。 */
        public float fraction() {
            return totalBytes <= 0 ? 0f : Math.min(1f, (float) downloadedBytes / (float) totalBytes);
        }
    }

    public interface ProgressListener {
        void onProgress(Progress p);
    }

    private static final class Candidate {
        final String url;
        final String host;
        long speed;
        boolean probed;

        Candidate(String url) {
            this.url = url;
            this.host = hostOf(url);
        }
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
     * 下载 APK（阻塞，必须在后台线程调用）：多直链测速择优 + 失败/掉速自动切源。
     *
     * @param urls           候选直链（1~N 条），由 {@link UpdateChecker} 合并两个通道得到
     * @param expectedSha256 期望的 SHA-256（小写十六进制），**必填**：
     *                       缺失时直接拒绝下载——没有完整性依据的包绝不落盘、绝不安装。
     * @return 下载完成的文件
     */
    public static File download(Context ctx, List<String> urls, String expectedSha256,
                                ProgressListener listener) throws Exception {
        List<String> clean = normalize(urls);
        if (clean.isEmpty()) {
            throw new IllegalStateException("没有可用的下载直链");
        }
        if (expectedSha256 == null || expectedSha256.trim().isEmpty()) {
            throw new IllegalStateException("缺少 SHA-256，拒绝下载");
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有下载进行中");
        }
        try {
            return downloadLocked(ctx, clean, expectedSha256, listener);
        } finally {
            DOWNLOADING.set(false);
        }
    }

    /** 单直链重载（保留给只有一条源/外部调用的场景）。 */
    public static File download(Context ctx, String url, String expectedSha256,
                                ProgressListener listener) throws Exception {
        return download(ctx, url == null ? Collections.<String>emptyList()
                : Collections.singletonList(url), expectedSha256, listener);
    }

    private static List<String> normalize(List<String> urls) {
        List<String> out = new ArrayList<>();
        if (urls == null) {
            return out;
        }
        for (String u : urls) {
            if (u == null) {
                continue;
            }
            String v = u.trim();
            if (!v.isEmpty() && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static File downloadLocked(Context ctx, List<String> urls, String expectedSha256,
                                       ProgressListener listener) throws Exception {
        File dir = targetDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建下载目录: " + dir);
        }

        List<Candidate> candidates = rankSources(ctx, urls);

        Exception last = null;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            try {
                File out = downloadFrom(ctx, c, expectedSha256, listener, i + 1,
                        candidates.size());
                rememberBestHost(ctx, c.host);
                return out;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "源 " + (i + 1) + "/" + candidates.size() + " 失败（" + c.host + "）: "
                        + e.getMessage());
            }
        }
        throw last != null ? last : new IllegalStateException("下载失败");
    }

    // —— 选源 ——

    /**
     * 按实测吞吐排序候选源。只有一条时直接返回（不测速，省一次请求）。
     * 测速全部失败时保持原始顺序（仍然要试，测速失败不等于下载必然失败——
     * 例如源对 Range 请求不友好）。
     */
    private static List<Candidate> rankSources(Context ctx, List<String> urls) {
        List<Candidate> list = new ArrayList<>();
        for (String u : urls) {
            list.add(new Candidate(u));
        }
        if (list.size() == 1) {
            return list;
        }
        String preferred = preferredHost(ctx);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(list.size(), 3));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Candidate c : list) {
                futures.add(pool.submit(() -> probe(c)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(PROBE_DEADLINE_MS + 3000L, TimeUnit.MILLISECONDS);
                } catch (Throwable t) {
                    Log.w(TAG, "测速任务未按时完成", t);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // 排序：探测成功且更快的在前；探测失败的垫底（保持原相对顺序，靠稳定排序）
        Collections.sort(list, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                if (a.probed != b.probed) {
                    return a.probed ? -1 : 1;
                }
                return Long.compare(b.speed, a.speed);
            }
        });

        // 上次成功的主机若速度不比最快源差太多，就继续用它，减少来回跳
        if (preferred != null && !list.isEmpty()) {
            Candidate fastest = list.get(0);
            for (int i = 1; i < list.size(); i++) {
                Candidate c = list.get(i);
                if (c.probed && c.host.equals(preferred)
                        && c.speed >= fastest.speed * PREFERRED_HOST_RATIO) {
                    list.remove(i);
                    list.add(0, c);
                    break;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Candidate c : list) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(c.host).append('=').append(c.speed / 1024).append("KB/s")
                    .append(c.probed ? "" : "(未知)");
        }
        Log.i(TAG, "选源结果: " + sb);
        return list;
    }

    /** Range 请求拉一小段实测吞吐；失败只置 probed=false，不抛异常。 */
    private static void probe(Candidate c) {
        HttpURLConnection conn = null;
        long start = System.currentTimeMillis();
        long read = 0L;
        try {
            conn = open(c.url);
            conn.setReadTimeout((int) PROBE_DEADLINE_MS);
            conn.setRequestProperty("Range", "bytes=0-" + (PROBE_BYTES - 1));
            int code = conn.getResponseCode();
            // 206=支持 Range；200=服务器忽略 Range 返回整包，读够取样量就断开
            if (code != HttpURLConnection.HTTP_OK && code != 206) {
                throw new IllegalStateException("HTTP " + code);
            }
            byte[] buf = new byte[8192];
            long deadline = start + PROBE_DEADLINE_MS;
            try (InputStream in = conn.getInputStream()) {
                int n;
                while (read < PROBE_BYTES) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    conn.setReadTimeout((int) Math.min(TIMEOUT, Math.max(300L, left)));
                    n = in.read(buf, 0, (int) Math.min(buf.length, PROBE_BYTES - read));
                    if (n <= 0) {
                        break;
                    }
                    read += n;
                }
            }
            long elapsed = Math.max(1L, System.currentTimeMillis() - start);
            c.speed = read * 1000L / elapsed;
            c.probed = read > 0;
        } catch (Throwable t) {
            Log.w(TAG, "测速失败（" + c.host + "）: " + t.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // —— 单源下载 ——

    private static File downloadFrom(Context ctx, Candidate c, String expectedSha256,
                                     ProgressListener listener, int index, int count)
            throws Exception {
        File dir = targetDir(ctx);
        File part = new File(dir, FILE_NAME + ".part");
        File dest = downloadedFile(ctx);
        // 不做续传：残留的半截文件一律丢弃重下（截断写入即使 delete 失败也能丢弃旧内容）
        if (part.exists() && !part.delete()) {
            Log.w(TAG, "删除残留 .part 失败（改用截断写入）: " + part);
        }

        HttpURLConnection conn = open(c.url);
        try {
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
                        listener.onProgress(new Progress(downloaded, total, speed, index, count));
                        lastReport = now;
                        lastBytes = downloaded;
                    }
                }
            }
            if (downloaded <= 0L) {
                throw new IllegalStateException("下载内容为空");
            }
            String actual = sha256Hex(digest);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                // 源被污染或下载损坏，绝不安装
                deleteQuietly(part);
                deleteQuietly(dest);
                throw new IllegalStateException("APK SHA-256 校验失败：期望 " + expectedSha256
                        + "，实际 " + actual);
            }
            // 先删掉旧的再 rename，否则 rename 可能失败
            deleteQuietly(dest);
            if (!part.renameTo(dest)) {
                throw new IllegalStateException("下载完成但重命名失败: " + part);
            }
            if (listener != null) {
                listener.onProgress(new Progress(downloaded, downloaded, 0, index, count));
            }
            return dest;
        } catch (Throwable t) {
            // 失败时清掉半截文件，避免残留
            deleteQuietly(part);
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new IllegalStateException(String.valueOf(t));
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "AdbSwitch-Update");
        return conn;
    }

    // —— 上次最优主机 ——

    private static String preferredHost(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_BEST_HOST, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void rememberBestHost(Context ctx, String host) {
        if (host == null || host.isEmpty()) {
            return;
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_BEST_HOST, host).apply();
        } catch (Throwable t) {
            Log.w(TAG, "记录最优主机失败", t);
        }
    }

    private static String hostOf(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    // —— 安装 ——

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

    private static String sha256Hex(MessageDigest digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
            return sha256Hex(digest).equalsIgnoreCase(expectedSha256);
        } catch (Throwable t) {
            Log.w(TAG, "SHA-256 校验异常: " + f, t);
            return false;
        }
    }
}
