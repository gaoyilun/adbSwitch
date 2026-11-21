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

/**
 * 在线更新检测：GitHub（主）+ 蒲公英（备）双通道。
 *
 * 移植自 LivePhoto 项目的同名实现（Kotlin），按本工程实际情况做了三处调整：
 *  1. Kotlin 协程 → 同步方法（调用方放在后台线程，本工程的 su/Shizuku 通道也是同一套后台线程模型）；
 *  2. 不提供「忽略」：本 App 走弱提示路线（启动只 Toast、下载入口在关于弹窗），
 *     不需要「永久别再提醒」的出口，故不持久化任何忽略状态；
 *  3. 把版本比较抽成纯函数 isNewer()，便于单测与复用。
 *
 * 通道编排（与原实现一致，这里写清理由）：
 *   GitHub 有更新 → 直接返回，不再查蒲公英；
 *   GitHub 无更新但不失败 → **继续**查蒲公英。否则「蒲公英有新包、GitHub 没有」时永远弹不出窗；
 *   GitHub 失败 → 降级到蒲公英；
 *   两个通道都成功且无更新 → 返回 hasUpdate=false，调用方写入节流时间戳；
 *   两个通道都失败 → 抛异常，调用方**不写**节流，下次启动重试（弱网用户不该被节流 3 天）。
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
     * @return 至少一个通道成功确认的结果（hasUpdate 可能为 false）；
     *         所有通道都失败时抛异常，调用方应静默忽略且不要写节流。
     */
    public static UpdateInfo check(int currentVersionCode, String currentVersionName)
            throws Exception {
        UpdateInfo github = null;
        if (githubEnabled()) {
            try {
                github = fetchGitHub(currentVersionCode);
            } catch (Throwable t) {
                Log.w(TAG, "GitHub 更新检查失败", t);
            }
            if (github != null && github.hasUpdate) {
                return withFallback(github, UpdateConfig.PGYER_PAGE_URL);
            }
        }

        if (pgyerEnabled()) {
            UpdateInfo pgyer = null;
            try {
                pgyer = fetchPgyer(currentVersionName);
            } catch (Throwable t) {
                Log.w(TAG, "蒲公英更新检查失败", t);
            }
            if (pgyer != null && pgyer.hasUpdate) {
                return withFallback(pgyer, UpdateConfig.GITHUB_RELEASES_URL);
            }
            if (pgyer != null) {
                return pgyer;
            }
        }

        if (github != null) {
            return github;
        }
        throw new IllegalStateException("所有更新通道检查失败");
    }

    /**
     * 给主通道结果补上「备用下载」入口（蒲公英通道没有直链，只能跳页面）。
     *
     * 主通道自带的 fallbackUrl 优先（如蒲公英返回的安装页 appURl），外部传入的只作兜底：
     * 直接覆写会把「蒲公英有新包」时的安装页丢成 GitHub，两个按钮跳同一处。
     */
    private static UpdateInfo withFallback(UpdateInfo info, String fallbackUrl) {
        String fb = (fallbackUrl == null || fallbackUrl.trim().isEmpty()) ? null : fallbackUrl.trim();
        String own = (info.fallbackUrl == null || info.fallbackUrl.trim().isEmpty())
                ? null : info.fallbackUrl.trim();
        String effective = own != null ? own : fb;
        return new UpdateInfo(info.hasUpdate, info.versionCode, info.versionName, info.notes,
                info.mandatory, info.apkUrl, effective, info.sha256, info.source);
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
        if (apkUrl != null && !isTrustedApkUrl(apkUrl)) {
            // update.json 被篡改/误填时不能把用户引向任意包源：降级为跳页面（fallbackUrl）
            Log.w(TAG, "apkUrl 非可信 GitHub https 直链，降级为页面跳转: " + apkUrl);
            apkUrl = null;
        }
        return new UpdateInfo(
                hasUpdate,
                remoteCode,
                obj.optString("versionName", ""),
                obj.optString("notes", ""),
                obj.optBoolean("mandatory", false),
                apkUrl,
                emptyToNull(obj.optString("fallbackUrl")),
                emptyToNull(obj.optString("sha256")),
                hasUpdate ? UpdateInfo.Source.GITHUB : UpdateInfo.Source.NONE);
    }

    /** APK 直链只认 GitHub 官方域名的 https 地址（含 release 资产跳转的 objects.githubusercontent.com）。 */
    private static boolean isTrustedApkUrl(String url) {
        try {
            URL u = new URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol())) {
                return false;
            }
            String host = u.getHost().toLowerCase(java.util.Locale.ROOT);
            return host.equals("github.com") || host.endsWith(".githubusercontent.com");
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
            // 关键：蒲公英官方文档中 buildVersionNo 是 String 类型（"Android Version Code，默认 1"），
            // 用 optInt 解析字符串会恒返回 0，导致「关于行可更新提示」与「一次性 Toast 去重」失效。
            // 必须按字符串解析（见 parseIntSafe）。
            return new UpdateInfo(
                    hasUpdate,
                    parseIntSafe(data.optString("buildVersionNo", "0")),
                    data.optString("buildVersion", ""),
                    data.optString("buildUpdateDescription", ""),
                    false,
                    null,
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
