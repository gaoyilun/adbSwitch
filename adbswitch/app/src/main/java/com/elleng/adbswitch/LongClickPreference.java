package com.elleng.adbswitch;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/**
 * 支持「整行长按」的普通 Preference。
 *
 * 当前 androidx.preference 1.2.1 的 Preference 没有提供
 * setOnPreferenceLongClickListener（那是更高版本才加入的 API），
 * 这里在行绑定时给 itemView 挂长按监听：RecyclerView 行复用每次绑定都会重挂，
 * 天然与行状态同步，无需额外的复用清理。
 */
public class LongClickPreference extends Preference {

    /** 长按整行回调（UI 线程）；返回 true 表示已消费 */
    public interface OnRowLongClickListener {
        boolean onRowLongClick();
    }

    private OnRowLongClickListener longClickListener;

    public LongClickPreference(Context context) {
        super(context);
    }

    public LongClickPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LongClickPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnRowLongClickListener(OnRowLongClickListener l) {
        longClickListener = l;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View row = holder.itemView;
        if (longClickListener != null) {
            row.setOnLongClickListener(v -> longClickListener.onRowLongClick());
            // 使长按生效：itemView 需要处于 long-clickable 状态
            row.setLongClickable(true);
        } else {
            row.setOnLongClickListener(null);
        }
    }
}
