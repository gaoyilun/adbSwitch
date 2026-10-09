package com.elleng.adbswitch.update;

/**
 * 在线更新的全部可配置项。
 *
 * ⚠️ 当前状态：**尚未激活**。原因与后续启用步骤见下。
 *
 * 启用步骤（正式上线后再做，现在不要填）：
 *  1. 把 {@link #ENABLED} 改为 true；
 *  2. 填 GitHub 通道：{@link #GITHUB_UPDATE_JSON_URL}（自维护的 update.json 直链）
 *     与 {@link #GITHUB_RELEASES_URL}（Release 页，弹窗「备用下载」入口）；
 *  3. 填蒲公英通道：{@link #PGYER_API_KEY} + {@link #PGYER_APP_KEY}
 *     + {@link #PGYER_PAGE_URL}；
 *  4. 在 update.json 里下发 versionCode / apkUrl / sha256，并保证 versionCode 单调递增、
 *     与 app/build.gradle.kts 的 versionCode 严格对齐。
 *
 * 为什么默认全部留空而不是填好「将来要用的值」：
 *  URL 一旦非空，App 启动就会真的发请求，测试环境就会拉到未发布的包；
 * 空值 + {@link #ENABLED} 关闭是双重保险，代码路径存在但绝不会触发。
 *
 * 安全提示：蒲公英的 _api_key 会打进安装包、可被提取。它只用于「查询有没有新版」，
 * 不用于下载（下载走蒲公英页面），风险可控。真正的完整性保障应依赖 update.json 下发的 sha256。
 */
public final class UpdateConfig {

    private UpdateConfig() {
    }

    /** 总开关。false 时 UpdateChecker 直接返回、一次网络请求都不会发。 */
    public static final boolean ENABLED = false;

    // —— GitHub 通道：拉自维护的 update.json ——
    // 预期形如：
    //   https://raw.githubusercontent.com/<owner>/<repo>/main/update/update.json
    public static final String GITHUB_UPDATE_JSON_URL = "";
    // 预期形如：https://github.com/<owner>/<repo>/releases
    public static final String GITHUB_RELEASES_URL = "";

    // —— 蒲公英通道 ——
    // 检测接口：POST https://www.pgyer.com/apiv2/app/check
    //         参数 _api_key / appKey / buildVersion（buildVersion 传 versionName）
    public static final String PGYER_CHECK_URL = "";
    public static final String PGYER_API_KEY = "";
    public static final String PGYER_APP_KEY = "";
    // 预期形如：https://www.pgyer.com/<AppName>
    public static final String PGYER_PAGE_URL = "";

    /** 检查节流：3 天内最多检查一次，本 App 非高频更新，弱提示即可，避免无谓网络请求。 */
    public static final long CHECK_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000;
}
