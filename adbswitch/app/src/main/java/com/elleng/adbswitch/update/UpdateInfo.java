package com.elleng.adbswitch.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次检查的结果。GitHub 与蒲公英两个通道的结果会按版本合并：
 * 版本说明、SHA-256 等元数据互补，两条直链一起放进 {@link #apkUrls} 供下载器测速择优。
 */
public final class UpdateInfo {

    /** 结果来自哪个通道。 */
    public enum Source {
        GITHUB,
        PGYER,
        NONE
    }

    /** 是否真的有新版（false 表示「已确认是最新」，不是「检查失败」）。 */
    public final boolean hasUpdate;
    /** 远端 versionCode。GitHub 通道里这是唯一的版本判定依据。 */
    public final int versionCode;
    /** 远端版本名，仅用于展示，不参与比较。 */
    public final String versionName;
    /** 更新说明，可能为空串。 */
    public final String notes;
    /** 强制更新：为 true 时不允许点外部/返回关闭。 */
    public final boolean mandatory;
    /**
     * 可用的 APK 直链列表（0~2 条）：GitHub Release 资产 + 蒲公英签名直链。
     * 顺序不代表优先级——下载器会先测速再选。
     */
    public final List<String> apkUrls;
    /** 备用下载入口（页面）：打开安装页/发布页，供直链全部失败时使用。 */
    public final String fallbackUrl;
    /** APK 的 SHA-256（十六进制小写）；服务器未下发时为 null，此时不做应用内直链下载。 */
    public final String sha256;
    public final Source source;

    public UpdateInfo(boolean hasUpdate, int versionCode, String versionName, String notes,
                      boolean mandatory, List<String> apkUrls, String fallbackUrl, String sha256,
                      Source source) {
        this.hasUpdate = hasUpdate;
        this.versionCode = versionCode;
        this.versionName = versionName == null ? "" : versionName;
        this.notes = notes == null ? "" : notes;
        this.mandatory = mandatory;
        this.apkUrls = apkUrls == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(apkUrls));
        this.fallbackUrl = fallbackUrl;
        this.sha256 = sha256;
        this.source = source;
    }
}
