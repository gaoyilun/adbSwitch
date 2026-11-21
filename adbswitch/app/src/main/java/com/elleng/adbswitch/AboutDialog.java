package com.elleng.adbswitch;

import android.app.Activity;
import android.content.Intent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 「关于」弹窗：版本号 / 包名 / 构建时间 / 当前授权模式。
 *
 * 构建时间放在这里而不是行摘要上：行摘要只放一行字，塞不下这些；
 * 而「设备上装的是不是最新版本」恰恰是排查问题时最常要的信息，
 * 放进弹窗后仍可一键查看，不会因为折叠而看不到。
 */
public final class AboutDialog {

    private static final String CREDIT_URL = "https://elleng.eu.org";
    private static final String REPO_URL = "https://github.com/gaoyilun/AdbSwitch";

    private AboutDialog() {
    }

    /**
     * @param privilegedAvailable 当前授权通道是否可用（决定授权模式行显示已授权/未授权）
     * @param onCheckUpdate       「检查更新」按钮回调（主线程）
     */
    public static void show(Activity activity, boolean privilegedAvailable, Runnable onCheckUpdate) {
        String buildTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault())
                .format(new java.util.Date(BuildConfig.BUILD_TIME_MS));
        AuthManager.Mode mode = AuthManager.getMode(activity);

        // 授权模式行与主界面「授权模式」保持一致文案（各模式 · 已授权/未授权）
        int authStatusRes;
        if (mode == AuthManager.Mode.SECURE_SETTINGS) {
            authStatusRes = privilegedAvailable ? R.string.auth_status_wss_ok : R.string.auth_status_wss_no;
        } else if (mode == AuthManager.Mode.SHIZUKU) {
            authStatusRes = privilegedAvailable ? R.string.auth_status_shizuku_ok : R.string.auth_status_shizuku_no;
        } else {
            authStatusRes = privilegedAvailable ? R.string.auth_status_root_ok : R.string.auth_status_root_no;
        }

        // 结构化布局（dialog_about.xml）：直接给各 TextView 设文本，不使用带 %1$s/%1$d 占位符的字符串，
        // 避免占位符未被 String.format 填充而原样显示。
        android.view.View view = activity.getLayoutInflater().inflate(R.layout.dialog_about, null);
        ((TextView) view.findViewById(R.id.about_name_version))
                .setText(activity.getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME);
        ((TextView) view.findViewById(R.id.about_package)).setText(activity.getPackageName());
        ((TextView) view.findViewById(R.id.about_build_time))
                .setText(activity.getString(R.string.about_build_time_label) + buildTime);
        ((TextView) view.findViewById(R.id.about_auth_mode)).setText(activity.getString(authStatusRes));
        // 右侧信息图标：跳转项目仓库
        view.findViewById(R.id.about_info).setOnClickListener(v -> openUrl(activity, REPO_URL));

        final AlertDialog dlg = new MaterialAlertDialogBuilder(activity)
                .setView(view)
                .setNegativeButton(R.string.about_credit, (d, which) -> openUrl(activity, CREDIT_URL))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.update_check, null) // 点击逻辑在 onShow 里接管
                .create();
        dlg.setOnShowListener(d -> {
            Button neutral = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
            // 「检查更新」常驻可见（待激活）：功能未配置时仍可点，提示尚未配置；
            // 比「未上线就藏起来」更符合预期——关于页一眼能看到有此功能，配置好后即生效。
            neutral.setOnClickListener(v -> {
                if (onCheckUpdate != null) {
                    onCheckUpdate.run();
                }
            });
        });
        dlg.show();
    }

    /** 跳转外部链接（署名按钮与信息图标用）；失败给 Toast 不闪退 */
    private static void openUrl(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(activity, R.string.about_open_failed, Toast.LENGTH_SHORT).show();
        }
    }
}