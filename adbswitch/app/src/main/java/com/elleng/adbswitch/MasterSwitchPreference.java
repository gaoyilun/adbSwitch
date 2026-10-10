package com.elleng.adbswitch;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 主开关行：普通 Preference + 自定义 widget（折叠箭头 + MaterialSwitch）。
 * 不用 SwitchPreferenceCompat 是因为行内还有折叠箭头，箭头点击与开关拨动必须分开处理。
 *
 * 为什么必须子类化并覆写 {@link #onBindViewHolder}：
 * 主开关的勾选态要在**行绑定时**（绘制之前）就摆好。若等布局完成后再手动 setChecked，
 * 行绑定到绘制之间的窗口期里 Switch 停在 XML 默认的关闭态 —— 冷启动就会看到
 * 「先显示关闭、随后跳成开启」的闪变（全局布局回调还不可靠，窗口更长）。
 *
 * 显示规则（与 MainActivity.refresh 一致）：
 *  - 有「上次确认状态」：直接显示它，**不置灰**（每次启动都置灰会有卡顿感），
 *    真值与它不一致时校准回来再修正一次（少数场景，可接受）；
 *  - 首次使用（无上次状态）：关 + 置灰，直到真值读回；
 *  - 「打开到真值读回」这个窗口期里发生点击：直接屏蔽（弹回显示值、不执行）。
 *
 * androidx 版差异（相对旧 framework 实现）：
 *  - 整行点击由 androidx 原生机制触发 OnPreferenceClickListener，不再需要给 itemView 手动挂监听；
 *  - 折叠箭头的接线收进 onBindViewHolder（RecyclerView 行复用时每次绑定都重挂，无需全局布局回调）；
 *  - Switch 换成 M3 MaterialSwitch（观感与其它 SwitchPreferenceCompat 行统一）。
 */
public class MasterSwitchPreference extends Preference {

    /** 开关拨动回调（UI 线程）；由 MainActivity 注入真正的执行逻辑 */
    public interface OnToggleListener {
        void onToggle(boolean on);
    }

    /** 折叠箭头点击回调；由 MainActivity 注入展开/折叠逻辑 */
    public interface OnExpandListener {
        void onExpandClicked();
    }

    private OnToggleListener toggleListener;
    private OnExpandListener expandListener;
    /** 当前绑定行里的 Switch（行未绑定时为 null） */
    private MaterialSwitch boundSwitch;
    /** 当前绑定行视图（更新箭头图标时直接在行内找，不做全树遍历） */
    private View boundRow;
    /** 当前显示的开关状态：窗口期点击被屏蔽时弹回它 */
    private boolean lastShownOn = false;
    /** 防止 setChecked 回灌触发 onCheckedChanged */
    private boolean syncing = false;
    /** 折叠箭头当前指向（true=已展开）；绑定与外部刷新共用 */
    private boolean expandedState = true;
    /** Preference 级 enabled 的镜像（未授权时 applyRootState 会 setEnabled(false)）。
     *  Switch widget 的 enabled 必须 = 它 && known —— 曾有缺陷：widget 只看 known，
     *  未授权时整行点击被禁但直接拨 Switch 仍能触发写入，绕过了授权门禁。 */
    private boolean prefEnabled = true;
    /** 最近一次绑定的 known（首次使用未校准时的置灰维度） */
    private boolean lastKnownAllowed = true;

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

    public void setOnExpandListener(OnExpandListener l) {
        expandListener = l;
    }

    /** Preference 级 enabled 变化时立即同步已绑定的 Switch（未授权置灰必须拦住 widget 本身） */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        prefEnabled = enabled;
        if (boundSwitch != null) {
            boundSwitch.setEnabled(enabled && lastKnownAllowed);
        }
    }

    /**
     * 真值刷新后由 MainActivity 调用，立即同步已绑定行里的 Switch；
     * 行尚未绑定时不做任何事（绑定时 {@link #onBindViewHolder} 会按同一规则摆好）。
     */
    public void applyState(boolean on, boolean enabled) {
        if (boundSwitch == null) {
            return;
        }
        lastShownOn = on;
        lastKnownAllowed = enabled;
        syncing = true;
        boundSwitch.setChecked(on);
        boundSwitch.setEnabled(enabled && prefEnabled);
        syncing = false;
    }

    /** 更新折叠箭头指向（MainActivity 折叠/展开两个子开关行后调用）；已绑定行立即生效 */
    public void setExpandedState(boolean expanded) {
        expandedState = expanded;
        ImageButton arrow = boundRow == null ? null : boundRow.findViewById(R.id.btn_expand);
        if (arrow != null) {
            arrow.setImageResource(expanded ? R.drawable.ic_collapse : R.drawable.ic_expand);
            arrow.setContentDescription(getContext().getString(
                    expanded ? R.string.master_collapse_desc : R.string.master_expand_desc));
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View view = holder.itemView;
        boundRow = view;
        ImageButton arrow = view.findViewById(R.id.btn_expand);
        if (arrow != null) {
            // 箭头单独消费点击（整行点击仍走 OnPreferenceClickListener 触发主开关切换）
            arrow.setOnClickListener(v -> {
                if (expandListener != null) {
                    expandListener.onExpandClicked();
                }
            });
            arrow.setImageResource(expandedState ? R.drawable.ic_collapse : R.drawable.ic_expand);
            // 无障碍：箭头随展开/折叠态播报对应语义（P3）
            arrow.setContentDescription(getContext().getString(
                    expandedState ? R.string.master_collapse_desc : R.string.master_expand_desc));
        }
        MaterialSwitch sw = view.findViewById(R.id.sw_master);
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
        lastKnownAllowed = known;
        // 先摘监听再摆状态，避免绑定时误触发拨动回调
        sw.setOnCheckedChangeListener(null);
        syncing = true;
        sw.setChecked(on);
        // 双重门禁：known（首次未校准置灰）&& Preference 级 enabled（未授权置灰）
        sw.setEnabled(known && isEnabled());
        syncing = false;
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncing) {
                return;
            }
            // 启动窗口期（真值尚未读回）的点击直接屏蔽：弹回显示值、不执行，并给一次性轻提示
            if (!DebugController.confirmedThisSession()) {
                MainActivity.toastReading(getContext());
                applyState(lastShownOn, true);
                return;
            }
            if (toggleListener != null) {
                toggleListener.onToggle(isChecked);
            }
        });
    }
}
