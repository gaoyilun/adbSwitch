package com.elleng.adbswitch;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Switch;

/**
 * 主开关行：普通 Preference + 自定义 widget（折叠箭头 + Switch）。
 * 不用 SwitchPreference 是因为行内还有折叠箭头，箭头点击与开关拨动必须分开处理。
 *
 * 为什么必须子类化并覆写 {@link #onBindView}：
 * 主开关的勾选态要在**行绑定时**（绘制之前）就摆好。若等布局完成后再手动 setChecked，
 * 行绑定到绘制之间的窗口期里 Switch 停在 XML 默认的关闭态 —— 冷启动就会看到
 * 「先显示关闭、随后跳成开启」的闪变（HyperOS 上全局布局回调还不可靠，窗口更长）。
 *
 * 显示规则（与 MainActivity.refresh 一致）：
 *  - 有「上次确认状态」：直接显示它，**不置灰**（每次启动都置灰会有卡顿感），
 *    真值与它不一致时校准回来再修正一次（少数场景，可接受）；
 *  - 首次使用（无上次状态）：关 + 置灰，直到真值读回；
 *  - 「打开到真值读回」这个窗口期里发生点击：直接屏蔽（弹回显示值、不执行）。
 */
public class MasterSwitchPreference extends Preference {

    /** 开关拨动回调（UI 线程）；由 MainActivity 注入真正的执行逻辑 */
    public interface OnToggleListener {
        void onToggle(boolean on);
    }

    private OnToggleListener toggleListener;
    /** 当前绑定行里的 Switch（行未绑定时为 null） */
    private Switch boundSwitch;
    /** 当前显示的开关状态：窗口期点击被屏蔽时弹回它 */
    private boolean lastShownOn = false;
    /** 防止 setChecked 回灌触发 onCheckedChanged */
    private boolean syncing = false;

    public MasterSwitchPreference(Context context) {
        super(context);
    }

    public MasterSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MasterSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnToggleListener(OnToggleListener l) {
        toggleListener = l;
    }

    /**
     * 真值刷新后由 MainActivity 调用，立即同步已绑定行里的 Switch；
     * 行尚未绑定时不做任何事（绑定时 {@link #onBindView} 会按同一规则摆好）。
     */
    public void applyState(boolean on, boolean enabled) {
        if (boundSwitch == null) {
            return;
        }
        lastShownOn = on;
        syncing = true;
        boundSwitch.setChecked(on);
        boundSwitch.setEnabled(enabled);
        syncing = false;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        Switch sw = view.findViewById(R.id.sw_master);
        boundSwitch = sw;
        if (sw == null) {
            return;
        }
        boolean calibrated = DebugController.isCalibrated();
        // 首次使用（历史上从没确认过真值）没有上次状态可显示，此时关 + 置灰；
        // 其余情况显示上次状态、不置灰
        boolean known = calibrated || DebugController.hasConfirmedBefore(getContext());
        boolean on = calibrated
                ? DebugController.read(getContext()).anyOn()
                : DebugController.lastKnownState(getContext()).anyOn();
        lastShownOn = on;
        // 先摘监听再摆状态，避免绑定时误触发拨动回调
        sw.setOnCheckedChangeListener(null);
        syncing = true;
        sw.setChecked(on);
        sw.setEnabled(known);
        syncing = false;
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncing) {
                return;
            }
            // 启动窗口期（真值尚未读回）的点击直接屏蔽：弹回显示值、不执行
            if (!DebugController.confirmedThisSession()) {
                applyState(lastShownOn, true);
                return;
            }
            if (toggleListener != null) {
                toggleListener.onToggle(isChecked);
            }
        });
    }
}
