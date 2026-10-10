package com.elleng.adbswitch.update;

import com.elleng.adbswitch.BuildConfig;

/**
 * 在线更新的全部可配置项。
 *
 * 双通道策略（GitHub 主 + 蒲公英备），编排与降级见 {@link UpdateChecker}。
 *
 * 配置来源：
 *  - 总开关 {@link #ENABLED}：构建时固定为 true。仅当它为真、且至少一条通道配置就绪时，
 *    {@link UpdateChecker#isActive()} 才返回 true、才会发请求（避免空配置误触发）。
 *  - 蒲公英密钥 {@link #PGYER_API_KEY} / {@link #PGYER_APP_KEY}：经 {@code BuildConfig}
 *    在构建时由仓库外的 {@code adbswitch/pgyer.properties} 注入，**不进源码、不进公开仓库**
 *    （该文件已被根 .gitignore 忽略；模板见 pgyer.properties.example）。缺文件或字段为空时注入空串，
 *    蒲公英通道自动降级（pgyerEnabled() 返回 false），干净 clone 不会发任何请求。
 *  - 蒲公英安装页 {@link #PGYER_PAGE_URL} 与 GitHub 两处 URL：非密钥、可入库，直接在此填写。
 *
 * 安全提示：蒲公英的 _api_key 会打进安装包、可被提取。它只用于「查询有没有新版」，
 * 不用于下载（下载走蒲公英页面），风险可控。真正的完整性保障依赖 update.json 下发的 sha256。
 *
 * 版本对齐（关键，详见下方字段注释与 UpdateChecker）：
 *  - GitHub 通道只认 update.json 的 versionCode（int），必须单调、与 app/build.gradle.kts 严格对齐；
 *  - 蒲公英通道由服务端按 buildVersion（传的是 versionName）判定 buildHaveNewVersion，
 *    故 versionName 也必须与 build.gradle.kts 严格对齐、单调递增。
 */
public final class UpdateConfig {

    private UpdateConfig() {
    }

    /** 总开关。true 时由 isActive() 据通道配置决定是否真的发请求。 */
    public static final boolean ENABLED = true;

    // —— GitHub 通道：拉自维护的 update.json ——
    // raw 直链对公开仓可用（私有仓返回 404）。本仓库为公开分发仓，仅含 update/ 元数据，不含源码。
    public static final String GITHUB_UPDATE_JSON_URL =
            "https://raw.githubusercontent.com/gaoyilun/adbSwitch/main/update/update.json";
    // GitHub Releases 落地页（含完整 APK 下载）。
    public static final String GITHUB_RELEASES_URL = "https://github.com/gaoyilun/adbSwitch/releases";

    // —— 蒲公英通道 ——
    // 检测接口：POST https://www.pgyer.com/apiv2/app/check（固定公开地址，无密钥）
    //         参数 _api_key / appKey / buildVersion（buildVersion 传 versionName，官方定义如此）
    public static final String PGYER_CHECK_URL = "https://www.pgyer.com/apiv2/app/check";

    /** 蒲公英 API Key（_api_key）：构建时由 pgyer.properties 注入，会进包、仅用于检测。 */
    public static final String PGYER_API_KEY = BuildConfig.PGYER_API_KEY;
    /** 蒲公英 App Key（应用 appKey）：构建时由 pgyer.properties 注入。 */
    public static final String PGYER_APP_KEY = BuildConfig.PGYER_APP_KEY;
    // 蒲公英安装页短链（已核对可达）：检测+弹窗+跳页 路径里「前往下载」的兜底落点。
    public static final String PGYER_PAGE_URL = "https://www.pgyer.com/adbSwitch";

    /** 检查节流：3 天内最多自动检查一次（手动「检查更新」不受限）。 */
    public static final long CHECK_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000;
}
