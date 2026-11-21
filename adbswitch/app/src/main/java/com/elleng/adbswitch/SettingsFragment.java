package com.elleng.adbswitch;

import android.os.Bundle;
import android.view.ViewGroup;

import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.RecyclerView;

/** 承载设置列表的 fragment：仅负责 inflate prefs.xml，业务与行刷新都在 MainActivity */
public class SettingsFragment extends PreferenceFragmentCompat {

    private RecyclerView recyclerView;
    /** 系统栏（导航栏）底部 inset：交给列表做内边距，最后一行才不会被导航栏压住 */
    private int insetBottom;
    /** 底部持久提示条（未授权 Snackbar）额外占位，见 {@link #setBottomExtraPadding(int)} */
    private int extraBottom;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, null);
        // 类型保护：requireActivity 的强转在异常复用路径下会以 ClassCastException
        // 直接把进程带崩；本 fragment 正常只挂在 MainActivity 容器里，加一道 instanceof 兜底。
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).onPreferencesCreated();
        }
    }

    @Override
    public RecyclerView onCreateRecyclerView(
            android.view.LayoutInflater inflater, ViewGroup parent,
            Bundle state) {
        RecyclerView rv = super.onCreateRecyclerView(inflater, parent, state);
        recyclerView = rv;
        // Android 15+ 强制 edge-to-edge：列表会延伸到导航栏底下，最后一行被遮住
        // （顶部状态栏 inset 已由 activity_main.xml 的 AppBarLayout 消费）。
        // 标准做法：clipToPadding=false + 把系统栏 inset 作为列表底部内边距，
        // 滚到末尾时最后一行仍完整可见。
        rv.setClipToPadding(false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rv, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            insetBottom = bars.bottom;
            applyBottomPadding(0);
            return insets;
        });
        // 视图可能已 attached：主动请求一次 inset 应用，否则首帧不生效
        androidx.core.view.ViewCompat.requestApplyInsets(rv);
        return rv;
    }

    @Override
    public void onDestroyView() {
        recyclerView = null;
        super.onDestroyView();
    }

    /**
     * 底部浮层（持久 Snackbar）占位：传入浮层顶边的窗口坐标，换算出列表需要额外留多少底部内边距。
     *
     * 提示条锚在窗口底部、是浮在列表之上的独立层级，列表不知道它存在，滚到末尾时最后两行
     * （授权模式 / 关于）就永远压在条下面 —— 既看不到也点不到。留出这份空白后列表还能继续
     * 往下滚，最后一行停在提示条上方。
     *
     * ⚠️ 这里刻意用「窗口坐标求重叠高度」而不是「用提示条自身高度」：Snackbar 会自己给
     * bottomMargin 让开导航栏，而列表底部内边距里已经含了同一份 inset，直接加高度会把
     * 导航栏高度算两遍，末尾凭空多出一大块白。按重叠算则与实际像素完全对齐。
     *
     * @param overlayTopInWindow 浮层顶边的窗口 y 坐标
     * @param gapPx              与浮层之间额外留的呼吸间距
     */
    void reserveBottomForOverlay(int overlayTopInWindow, int gapPx) {
        RecyclerView rv = recyclerView;
        if (rv == null) {
            return;
        }
        int[] loc = new int[2];
        rv.getLocationInWindow(loc);
        int rvBottom = loc[1] + rv.getHeight();
        int overlap = Math.max(0, rvBottom - overlayTopInWindow);
        // 导航栏那一段已由 insetBottom 覆盖，只需要补超出的部分
        setBottomExtraPadding(Math.max(0, overlap - insetBottom) + gapPx);
    }

    /** 浮层消失：撤掉占位，恢复正常底部内边距 */
    void clearBottomOverlay() {
        setBottomExtraPadding(0);
    }

    /**
     * @param px 额外底部内边距（像素），0 = 提示条已消失
     */
    void setBottomExtraPadding(int px) {
        if (px == extraBottom) {
            return;
        }
        int delta = px - extraBottom;
        extraBottom = px;
        applyBottomPadding(delta);
    }

    private void applyBottomPadding(int scrollDelta) {
        RecyclerView rv = recyclerView;
        if (rv == null) {
            return;
        }
        int target = insetBottom + extraBottom;
        if (rv.getPaddingBottom() == target) {
            return;
        }
        // 内边距只扩大可滚范围、不会移动已有子项：此刻若列表正贴着底部，
        // 最后一行仍原地被条压住。这类情况同步上推 delta，保持"贴底"的视觉位置不变。
        boolean atBottom = !rv.canScrollVertically(1);
        rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(), rv.getPaddingRight(), target);
        if (scrollDelta > 0 && atBottom) {
            // 等本次布局把新的可滚范围算出来再滚，否则 scrollBy 会被旧范围夹住
            rv.post(() -> rv.scrollBy(0, scrollDelta));
        }
    }
}
