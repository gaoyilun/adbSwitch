package com.elleng.adbswitch.update;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 在线更新检测：GitHub（主）+ 蒲公英（备）双通道。
 *
 * 移植自 LivePhoto 项目的同名实现（Kotlin），按本工程实际情况做了三处调整：
 *  1. Kotlin 协程 → 同步方法（调用方放在后台线程，本工程的 su/Shizuku 通道也是同一套后台线程模型）；
 *  2. 不提供「忽略」：本 App 走弱提示路线（启动只 Toast、下载入口在关于弹窗），
 *     不需要「永久别再提醒」的出口，故不持久化任何忽略状态；
 *  3. 把版本比较抽成纯函数 isNewer()，便于单测与复用。
 *
 * 通道编排：
 *   两个通道**并行**请求，各自成功后按版本合并（见 {@link #merge}）：
 *   同版本 → 元数据互补、两条直链一起交给下载器测速择优；
 *   只有一边有新版本 → 用那一边；
 *   两边都失败 → 抛异常，调用方**不写**节流，下次启动重试（弱网用户不该被节流 3 天）。
 *
 * 当前 {@link UpdateConfig#ENABLED} 为 true，GitHub 主通道已硬编码激活；仅在 {@link #isActive()} 判定通道就绪时才发请求。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String PREFS = "update_prefs";
    private static final String KEY_LAST_CHECK = "last_check";
    // 已知远端最新版本（供「关于」行离线显示「可更新」）
    private static final String KEY_LATEST_CODE = "latest_version_code";
    private static final String KEY_LATEST_NAME = "latest_version_name";
    // 自动检查「一次性 toast」去重：记下已提示过的版本号，避免每次启动都弹
    private static final String KEY_TOASTED_CODE = "toasted_version_code";

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 8000;
    /** 更新元数据响应上限：正常 JSON 只有几 KB，超限按异常响应拒绝，防止恶意/错误响应撑爆内存。 */
    private static final int MAX_JSON_CHARS = 256 * 1024;

    private UpdateChecker() {
    }

    /** 是否已具备检查条件：总开关打开，且至少配置了一个可用通道。 */
    public static boolean isActive() {
        if (!UpdateConfig.ENABLED) {
            return false;
        }
        return githubEnabled() || pgyerEnabled();
    }

    private static boolean githubEnabled() {
        return !UpdateConfig.GITHUB_UPDATE_JSON_URL.trim().isEmpty();
    }

    private static boolean pgyerEnabled() {
        return !UpdateConfig.PGYER_CHECK_URL.trim().isEmpty()
                && !UpdateConfig.PGYER_API_KEY.trim().isEmpty()
                && !UpdateConfig.PGYER_APP_KEY.trim().isEmpty();
    }

    // —— 节流 ——

    public static boolean shouldCheck(Context ctx) {
        long last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_CHECK, 0L);
        long now = System.currentTimeMillis();
        // 墙钟被回拨（now < last）时按已过期处理，否则会被节流到时钟追上为止
        return now < last || now - last >= UpdateConfig.CHECK_INTERVAL_MS;
    }

    /** 只在「成功拿到结果」时调用；网络失败不写，否则弱网用户 3 天内收不到更新。 */
    public static void markChecked(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    // —— 已知远端版本（关于行「可更新」提示，离线也可见）——

    /** 自动/手动检查发现新版本后记录远端版本号，使关于行离线也能显示「可更新」。 */
    public static void saveLatestKnown(Context ctx, int versionCode, String versionName) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_LATEST_CODE, versionCode)
                .putString(KEY_LATEST_NAME, versionName == null ? "" : versionName)
                .apply();
    }

    /** 已知远端最新 versionCode（0 表示从未检查到更新）。 */
    public static int getLatestVersionCode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_LATEST_CODE, 0);
    }

    public static String getLatestVersionName(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LATEST_NAME, "");
    }

    /** 是否存在已知的新版本（供关于行显示「可更新」）。 */
    public static boolean hasKnownUpdate(Context ctx, int currentCode) {
        return getLatestVersionCode(ctx) > currentCode;
    }

    // —— 自动检查的一次性 toast 去重 ——

    /** 记录已弹过 toast 的版本号，避免每个启动周期都重复提示。 */
    public static void markToasted(Context ctx, int versionCode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_TOASTED_CODE, versionCode).apply();
    }

    public static int toastedVersion(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TOASTED_CODE, 0);
    }

    // —— 检查入口（阻塞，必须在后台线程调用）——

    /**
     * 两个通道**并行**检测，再按版本合并结果：
     *  - 版本相同：元数据互补（GitHub 提供 sha256，蒲公英提供快速直链），两条直链一起交给下载器测速；
     *  - 只有一边有新版本：用那一边；直链缺失时退化为打开页面；
     *  - 两边都失败：抛异常，调用方不写节流，下次启动重试。
     *
     * 并行而不是「GitHub 优先、失败再查蒲公英」：串行时一个通道超时会把整体拖到 10 秒以上，
     * 而且「GitHub 能连但很慢」这种场景下也需要蒲公英的直链作为备选。
     *
     * @return 至少一个通道成功确认的结果（hasUpdate 可能为 false）
     */
    public static UpdateInfo check(int currentVersionCode, String currentVersionName)
            throws Exception {
        UpdateInfo github;
        UpdateInfo pgyer;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<UpdateInfo> ghFuture = null;
        Future<UpdateInfo> pgFuture = null;
        if (githubEnabled()) {
            ghFuture = pool.submit(() -> fetchGitHub(currentVersionCode));
        }
        if (pgyerEnabled()) {
            pgFuture = pool.submit(() -> fetchPgyer(currentVersionName));
        }
        try {
            github = await(ghFuture, "GitHub");
            pgyer = await(pgFuture, "蒲公英");
        } finally {
            pool.shutdownNow();
        }
        return merge(github, pgyer);
    }

    private static UpdateInfo await(Future<UpdateInfo> future, String name) {
        if (future == null) {
            return null;
        }
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Log.w(TAG, name + " 更新检查失败", t);
            return null;
        }
    }

    /** 合并两个通道的结果；只有一边成功时按那一边返回。 */
    private static UpdateInfo merge(UpdateInfo gh, UpdateInfo pg) {
        boolean ghNew = gh != null && gh.hasUpdate;
        boolean pgNew = pg != null && pg.hasUpdate;
        if (ghNew && pgNew) {
            if (pg.versionCode > gh.versionCode) {
                // 蒲公英版本更新（先传了包），但没有 sha256：只能跳页面，不能应用内直装
                return withFallback(pg, UpdateConfig.GITHUB_RELEASES_URL);
            }
            if (gh.versionCode > pg.versionCode) {
                return withFallback(gh, UpdateConfig.PGYER_PAGE_URL);
            }
            // 同版本：共享两边的下载直链，说明文字优先用 GitHub 的 update.json
            List<String> urls = new ArrayList<>();
            addTrusted(urls, gh.apkUrls);
            addTrusted(urls, pg.apkUrls);
            String notes = !gh.notes.trim().isEmpty() ? gh.notes : pg.notes;
            String fallback = emptyToNull(pg.fallbackUrl);
            return new UpdateInfo(true, gh.versionCode, gh.versionName, notes, gh.mandatory,
                    urls, fallback != null ? fallback : UpdateConfig.PGYER_PAGE_URL,
                    gh.sha256, UpdateInfo.Source.GITHUB);
        }
        if (ghNew) {
            return withFallback(gh, UpdateConfig.PGYER_PAGE_URL);
        }
        if (pgNew) {
            return withFallback(pg, UpdateConfig.GITHUB_RELEASES_URL);
        }
        if (gh != null) {
            return gh;
        }
        if (pg != null) {
            return pg;
        }
        throw new IllegalStateException("所有更新通道检查失败");
    }

    private static void addTrusted(List<String> out, List<String> urls) {
        for (String u : urls) {
            if (u != null && !u.trim().isEmpty() && isTrustedDirectUrl(u)) {
                String v = u.trim();
                if (!out.contains(v)) {
                    out.add(v);
                }
            }
        }
    }

    /**
     * 给主通道结果补上「备用下载」入口（页面）。主通道自带 fallbackUrl 优先。
     */
    private static UpdateInfo withFallback(UpdateInfo info, String fallbackUrl) {
        String fb = (fallbackUrl == null || fallbackUrl.trim().isEmpty()) ? null : fallbackUrl.trim();
        String own = (info.fallbackUrl == null || info.fallbackUrl.trim().isEmpty())
                ? null : info.fallbackUrl.trim();
        String effective = own != null ? own : fb;
        return new UpdateInfo(info.hasUpdate, info.versionCode, info.versionName, info.notes,
                info.mandatory, info.apkUrls, effective, info.sha256, info.source);
    }

    /**
     * GitHub 通道：拉自维护的 update.json。
     * versionCode 是唯一判定依据；versionName 只用于展示，tag 名前缀 v 之类完全不参与比较。
     */
    private static UpdateInfo fetchGitHub(int current) throws Exception {
        String json = getString(UpdateConfig.GITHUB_UPDATE_JSON_URL);
        JSONObject obj = new JSONObject(json);
        if (!obj.has("versionCode")) {
            // 字段缺失时 optInt 会静默回退成当前值 → 永远判「无更新」，必须留痕
            Log.w(TAG, "update.json 缺少 versionCode 字段，按无更新处理");
        }
        int remoteCode = obj.optInt("versionCode", current);
        boolean hasUpdate = isNewer(remoteCode, current);
        String apkUrl = hasUpdate ? emptyToNull(obj.optString("apkUrl")) : null;
        List<String> urls = new ArrayList<>();
        if (apkUrl != null) {
            if (isTrustedDirectUrl(apkUrl)) {
                urls.add(apkUrl);
            } else {
                // update.json 被篡改/误填时不能把用户引向任意包源：降级为跳页面（fallbackUrl）
                Log.w(TAG, "apkUrl 非可信直链，降级为页面跳转: " + apkUrl);
            }
        }
        return new UpdateInfo(
                hasUpdate,
                remoteCode,
                obj.optString("versionName", ""),
                obj.optString("notes", ""),
                obj.optBoolean("mandatory", false),
                urls,
                emptyToNull(obj.optString("fallbackUrl")),
                emptyToNull(obj.optString("sha256")),
                hasUpdate ? UpdateInfo.Source.GITHUB : UpdateInfo.Source.NONE);
    }

    /** 直链只认官方域名的 https 地址：GitHub 资产（含 objects.githubusercontent.com）与蒲公英签名链。 */
    private static boolean isTrustedDirectUrl(String url) {
        try {
            URL u = new URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol())) {
                return false;
            }
            String host = u.getHost().toLowerCase(java.util.Locale.ROOT);
            return host.equals("github.com")
                    || host.endsWith(".githubusercontent.com")
                    || host.equals("pgyer.com")
                    || host.endsWith(".pgyer.com");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 蒲公英通道：POST https://www.pgyer.com/apiv2/app/check
     * 参数只有 _api_key / appKey / buildVersion（传 versionName）；
     * 由服务端判断有没有新版（data.buildHaveNewVersion），客户端不做版本号比较。
     * 蒲公英 API 不提供强制更新标记，故 mandatory 恒为 false。
     */
    private static UpdateInfo fetchPgyer(String currentVersionName) throws Exception {
        HttpURLConnection conn =
                (HttpURLConnection) new URL(UpdateConfig.PGYER_CHECK_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "_api_key=" + enc(UpdateConfig.PGYER_API_KEY)
                    + "&appKey=" + enc(UpdateConfig.PGYER_APP_KEY)
                    + "&buildVersion=" + enc(currentVersionName);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }
            // 读完整响应而非首行：服务端若美化输出（多行 JSON），readLine 只拿到 "{" 直接解析失败
            String json = readAll(conn);
            JSONObject obj = new JSONObject(json);
            if (obj.optInt("code", -1) != 0) {
                throw new IllegalStateException(
                        "pgyer code=" + obj.optInt("code") + " " + obj.optString("message"));
            }
            JSONObject data = obj.optJSONObject("data");
            if (data == null) {
                throw new IllegalStateException("pgyer 无 data 字段");
            }
            boolean hasUpdate = data.optBoolean("buildHaveNewVersion", false);
            // 注意字段名就是官方那个拼写 appURl（不是 appUrl）
            String pageUrl = emptyToNull(data.optString("appURl"));
            // 蒲公英其实提供了签名直链（会 302 到 oss.pgyer.com），有效期数小时；
            // 它是国内下载的主用候选，是否采用由下载器测速决定。
            String directUrl = hasUpdate ? emptyToNull(data.optString("downloadURL")) : null;
            List<String> urls = new ArrayList<>();
            if (directUrl != null && isTrustedDirectUrl(directUrl)) {
                urls.add(directUrl);
            }
            // 关键：蒲公英官方文档中 buildVersionNo 是 String 类型（"Android Version Code，默认 1"），
            // 用 optInt 解析字符串会恒返回 0，导致「关于行可更新提示」与「一次性 Toast 去重」失效。
            // 必须按字符串解析（见 parseIntSafe）。
            return new UpdateInfo(
                    hasUpdate,
                    parseIntSafe(data.optString("buildVersionNo", "0")),
                    data.optString("buildVersion", ""),
                    data.optString("buildUpdateDescription", ""),
                    false,
                    urls,
                    hasUpdate ? (pageUrl != null ? pageUrl : emptyToNull(UpdateConfig.PGYER_PAGE_URL)) : null,
                    null,
                    hasUpdate ? UpdateInfo.Source.PGYER : UpdateInfo.Source.NONE);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 版本比较：只比 versionCode（单调递增整数）。
     * 抽成纯函数是为了能直接单测，也避免各处散落 ">" 造成不一致。
     */
    public static boolean isNewer(int remoteCode, int currentCode) {
        return remoteCode > currentCode;
    }

    private static String getString(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }
            return readAll(conn);
        } finally {
            conn.disconnect();
        }
    }

    /** 读完整响应文本，带 {@link #MAX_JSON_CHARS} 上限。 */
    private static String readAll(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0) {
                if (sb.length() + n > MAX_JSON_CHARS) {
                    throw new IllegalStateException("更新元数据响应超过 " + MAX_JSON_CHARS + " 字符上限");
                }
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /** 把字符串安全解析为 int；空/非数字都返回 0。蒲公英的 buildVersionNo 官方定义为 String 类型。 */
    private static int parseIntSafe(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
