package com.elleng.adbswitch;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.preference.SwitchPreferenceCompat;

/**
 * 调试三行（USB / 无线 / 开发者选项）的自定义开关：在 Switch 左侧多出一个可点击的
 * 「›」导航箭头，点击（或长按整行）跳转到系统对应设置项（见 {@link SettingsNav}）。
 *
 * 设计要点：
 *  - 开关拨动仍由 {@link MainActivity#bindSubSwitch} 收口（SwitchPreferenceCompat 原生行为）；
 *  - 箭头是独立的 {@link ImageButton}，消费自己的点击，不会触发整行/开关的切换；
 *    这与 MasterSwitchPreference 的折叠箭头同构（子视图消费点击 → 不冒泡到整行监听）。
 *  - 长按整行（itemView）也会触发同一导航：长按消费后返回 true，避免触发开关切换或其它默认行为；
 *    单击仍走开关原生 toggle，长按与单击相互独立、互不干扰。
 *  - 箭头默认 gone：只有调用方通过 {@link #setOnNavClickListener} 注入导航逻辑后才显示并接线，
 *    因此复用同一套 pref_row_state 布局的主开关行（无导航）不会多出一个箭头。
 *  - 箭头可用性只由「是否注入了导航监听」决定，与整行授权/置灰状态解耦：打开系统设置菜单
 *    不需要本 App 授权，故无论行是否处于「未授权/未确认窗口」置灰，箭头都保持可点。
 *  - 导航模式（{@link #setNavigateMode}）：未授权/未激活时，整行点击改为进入相应设置菜单，
 *    开关控件本身禁用（不能直接拨动）；已授权后整行点击切回操作开关。
 */
public class NavSwitchPreference extends SwitchPreferenceCompat {

    /** 箭头点击回调（UI 线程）；由 MainActivity 注入跳转逻辑 */
    public interface OnNavClickListener {
        void onNavClick();
    }

    private OnNavClickListener navListener;
    /** 当前绑定行里的箭头（行未绑定时为 null） */
    private ImageButton boundChevron;
    /** 导航模式：未授权/未激活为 true，整行点击改为进入设置菜单、开关禁用；已授权为 false，整行点击恢复拨动开关 */
    private boolean navigateMode = false;

    public NavSwitchPreference(Context context) {
        super(context);
    }

    public NavSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NavSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnNavClickListener(OnNavClickListener l) {
        navListener = l;
        // 行已绑定时（布局已就绪）立即按最新状态刷新箭头可见性与可用性
        if (boundChevron != null) {
            applyChevronState();
        }
    }

    /**
     * 设置导航模式。未授权 / 未激活时为 true：整行点击改为进入相应设置菜单，开关控件禁用
     * （不能直接拨动），但「›」箭头保持可点；已授权后为 false，整行点击恢复为拨动开关。
     */
    public void setNavigateMode(boolean navigate) {
        if (navigateMode == navigate) {
            return;
        }
        navigateMode = navigate;
        // 触发 onBindViewHolder 重绑，刷新整行点击路由与箭头可用性
        notifyChanged();
    }

    /** 行级 enabled 变化时同步已绑定的箭头（未授权置灰必须拦住导航入口，但导航模式例外） */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        applyChevronState();
    }

    private void applyChevronState() {
        if (boundChevron == null) {
            return;
        }
        if (navListener == null) {
            boundChevron.setVisibility(View.GONE);
            return;
        }
        boundChevron.setVisibility(View.VISIBLE);
        // 导航模式下即便整行被 framework 置为「禁用」置灰，箭头也保持可点
        // （打开系统设置菜单不需要本 App 授权）
        boundChevron.setEnabled(navigateMode || isEnabled());
    }

    /** 在行视图里递归找到 Switch 控件（导航模式下只禁用它，不影响整行点击） */
    private static View findSwitchWidget(View view) {
        if (view instanceof android.widget.CompoundButton) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findSwitchWidget(vg.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Override
    public void onBindViewHolder(androidx.preference.PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        ImageButton chevron = holder.itemView.findViewById(R.id.nav_chevron);
        boundChevron = chevron;
        if (chevron == null) {
            return;
        }
        // 每次绑定（含 RecyclerView 复用时）都重挂监听，避免复用导致旧行误触发
        chevron.setOnClickListener(v -> {
            if (navListener != null) {
                navListener.onNavClick();
            }
        });
        if (navigateMode) {
            // 导航模式（未授权/未激活）：整行点击进入相应设置菜单，开关控件禁用（不能拨动）。
            // 关键：整行保持 enabled（可点），只把 Switch 控件单独禁用，避免点到开关时触发切换。
            // （若整行 setEnabled(false) 会被 framework 屏蔽触摸事件，setClickable(true) 也无法补救，
            //  那样整行点击就收不到——这正是之前「整行点了没反应」的根因。）
            holder.itemView.setOnClickListener(v -> {
                if (navListener != null) {
                    navListener.onNavClick();
                }
            });
            View sw = findSwitchWidget(holder.itemView);
            if (sw != null) {
                sw.setEnabled(false);
            }
            holder.itemView.setOnLongClickListener(v -> {
                if (navListener != null) {
                    navListener.onNavClick();
                    return true;
                }
                return false;
            });
        } else {
            // 正常模式：整行点击交给 SwitchPreferenceCompat 原生 toggle；长按整行进入菜单。
            // 不覆盖 itemView 的 onClick，保留原生开关切换。
            holder.itemView.setOnLongClickListener(v -> {
                if (navListener != null && isEnabled()) {
                    navListener.onNavClick();
                    return true;
                }
                return false;
            });
        }
        applyChevronState();
    }
}
