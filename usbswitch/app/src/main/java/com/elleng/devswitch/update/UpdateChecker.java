package com.elleng.devswitch.update;

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
 *  2. 补上「已忽略版本」的持久化（见下方 ignoredVersion）——原实现点「忽略」只关弹窗，
 *     24 小时节流一过同一个版本会再弹一次，用户无法真正忽略；
 *  3. 把版本比较抽成纯函数 isNewer()，便于单测与复用。
 *
 * 通道编排（与原实现一致，这里写清理由）：
 *   GitHub 有更新 → 直接返回，不再查蒲公英；
 *   GitHub 无更新但不失败 → **继续**查蒲公英。否则「蒲公英有新包、GitHub 没有」时永远弹不出窗；
 *   GitHub 失败 → 降级到蒲公英；
 *   两个通道都成功且无更新 → 返回 hasUpdate=false，调用方写入节流时间戳；
 *   两个通道都失败 → 抛异常，调用方**不写**节流，下次启动重试（弱网用户不该被节流 24 小时）。
 *
 * 当前 {@link UpdateConfig#ENABLED} 为 false，本类一次网络请求都不会发。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String PREFS = "update_prefs";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_IGNORED = "ignored_version_code";

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 8000;

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

    // —— 节流与忽略 ——

    public static boolean shouldCheck(Context ctx) {
        long last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_CHECK, 0L);
        return System.currentTimeMillis() - last >= UpdateConfig.CHECK_INTERVAL_MS;
    }

    /** 只在「成功拿到结果」时调用；网络失败不写，否则弱网用户 24 小时内收不到更新。 */
    public static void markChecked(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    /** 用户点「忽略」后记下该 versionCode，之后同版本不再弹。 */
    public static void ignoreVersion(Context ctx, int versionCode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_IGNORED, versionCode).apply();
    }

    public static int ignoredVersion(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_IGNORED, 0);
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
                Log.w(TAG, "GitHub 更新检查失败: " + t.getMessage());
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
                Log.w(TAG, "蒲公英更新检查失败: " + t.getMessage());
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

    /** 给主通道结果补上「备用下载」入口（蒲公英通道没有直链，只能跳页面）。 */
    private static UpdateInfo withFallback(UpdateInfo info, String fallbackUrl) {
        String fb = (fallbackUrl == null || fallbackUrl.trim().isEmpty()) ? null : fallbackUrl;
        return new UpdateInfo(info.hasUpdate, info.versionCode, info.versionName, info.notes,
                info.mandatory, info.apkUrl, fb, info.sha256, info.source);
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
        return new UpdateInfo(
                hasUpdate,
                remoteCode,
                obj.optString("versionName", ""),
                obj.optString("notes", ""),
                obj.optBoolean("mandatory", false),
                hasUpdate ? emptyToNull(obj.optString("apkUrl")) : null,
                emptyToNull(obj.optString("fallbackUrl")),
                emptyToNull(obj.optString("sha256")),
                hasUpdate ? UpdateInfo.Source.GITHUB : UpdateInfo.Source.NONE);
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
            String json;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                json = br.readLine();
            }
            JSONObject obj = new JSONObject(json == null ? "" : json);
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
            return new UpdateInfo(
                    hasUpdate,
                    data.optInt("buildVersionNo", 0),
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
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
