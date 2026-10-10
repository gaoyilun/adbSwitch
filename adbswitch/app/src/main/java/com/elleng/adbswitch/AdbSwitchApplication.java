package com.elleng.adbswitch;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

/**
 * 自定义 Application：启用 M3 动态取色（莫奈）。
 * MDC 视图库必须在此处调用 DynamicColors.applyToActivitiesIfAvailable()，
 * 系统才会把壁纸派生色注入 DynamicColors 主题的颜色属性；否则只会落到 baseline 兜底色。
 * 必须在任何 Activity 创建前注册，故放在 Application.onCreate。
 */
public class AdbSwitchApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
