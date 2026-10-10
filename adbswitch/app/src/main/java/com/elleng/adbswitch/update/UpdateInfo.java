package com.elleng.adbswitch.update;

/**
 * 一次检查的结果。字段与 LivePhoto 项目的 UpdateInfo 保持一致，便于对照维护。
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
    /** 主通道 APK 直链；蒲公英通道恒为 null（那边只引导跳安装页）。 */
    public final String apkUrl;
    /** 备用下载入口：主通道可用时是蒲公英页，蒲公英通道可用时是 GitHub Release 页。 */
    public final String fallbackUrl;
    /** APK 的 SHA-256（十六进制小写）；服务器未下发时为 null，此时跳过完整性校验。 */
    public final String sha256;
    public final Source source;

    public UpdateInfo(boolean hasUpdate, int versionCode, String versionName, String notes,
                      boolean mandatory, String apkUrl, String fallbackUrl, String sha256,
                      Source source) {
        this.hasUpdate = hasUpdate;
        this.versionCode = versionCode;
        this.versionName = versionName == null ? "" : versionName;
        this.notes = notes == null ? "" : notes;
        this.mandatory = mandatory;
        this.apkUrl = apkUrl;
        this.fallbackUrl = fallbackUrl;
        this.sha256 = sha256;
        this.source = source;
    }
}
