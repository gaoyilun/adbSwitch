package com.elleng.adbswitch;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「管理开关项」弹窗：配置每项是否显示 / 是否跟随主开关，拖动柄排序。
 * 确认时落盘 SwitchConfig（显示 / 跟随 / 顺序）并标记快捷方式待重建，
 * 界面重建由调用方经 onApplied 回调完成（弹窗不持有主界面刷新逻辑）。
 */
public final class ManageItemsDialog {

    private ManageItemsDialog() {
    }

    /** @param onApplied 确认落盘后的主界面重建回调（主线程） */
    public static void show(Activity activity, Runnable onApplied) {
        final Adapter adapter = new Adapter(activity);
        final RecyclerView rv = new RecyclerView(activity);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        rv.setAdapter(adapter);
        final ItemTouchHelper touchHelper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                    @Override
                    public boolean onMove(RecyclerView recyclerView,
                                          RecyclerView.ViewHolder viewHolder,
                                          RecyclerView.ViewHolder target) {
                        adapter.move(viewHolder.getAdapterPosition(), target.getAdapterPosition());
                        return true;
                    }

                    @Override
                    public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                    }

                    @Override
                    public boolean isLongPressDragEnabled() {
                        return false; // 只用右侧拖动柄拖；整行长按不触发，避免误触
                    }
                });
        touchHelper.attachToRecyclerView(rv);
        adapter.dragStarter = touchHelper;

        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);
        root.addView(rv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.manage_items_title)
                .setView(root)
                .setPositiveButton(R.string.picker_confirm, (d, w) -> {
                    for (SwitchItem item : SwitchItem.values()) {
                        SwitchConfig.setShown(activity, item,
                                Boolean.TRUE.equals(adapter.shownMap.get(item)));
                        SwitchConfig.setFollowMaster(activity, item,
                                Boolean.TRUE.equals(adapter.followMap.get(item)));
                    }
                    SwitchConfig.setOrder(activity, adapter.order);
                    ToggleShortcuts.markDirty(activity);
                    if (onApplied != null) {
                        onApplied.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 弹窗行适配器：宽行（标题 + 显示/跟随 + 拖动柄），拖动柄触发排序 */
    private static final class Adapter extends RecyclerView.Adapter<Adapter.H> {
        final Context ctx;
        final List<SwitchItem> order = new ArrayList<>();
        final Map<SwitchItem, Boolean> shownMap = new HashMap<>();
        final Map<SwitchItem, Boolean> followMap = new HashMap<>();
        ItemTouchHelper dragStarter;

        Adapter(Context ctx) {
            this.ctx = ctx;
            order.addAll(SwitchConfig.getOrder(ctx));
            for (SwitchItem it : SwitchItem.values()) {
                shownMap.put(it, SwitchConfig.isShown(ctx, it));
                followMap.put(it, SwitchConfig.rawFollow(ctx, it));
            }
        }

        static final class H extends RecyclerView.ViewHolder {
            final TextView title;
            final CheckBox shown;
            final CheckBox follow;
            final ImageView handle;

            H(View v) {
                super(v);
                title = v.findViewById(R.id.manage_title);
                shown = v.findViewById(R.id.manage_shown_box);
                follow = v.findViewById(R.id.manage_follow_box);
                handle = v.findViewById(R.id.manage_drag);
            }
        }

        @Override
        public H onCreateViewHolder(ViewGroup parent, int viewType) {
            return new H(android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.manage_item_row, parent, false));
        }

        @Override
        public void onBindViewHolder(H h, int position) {
            final SwitchItem item = order.get(position);
            h.title.setText(itemTitle(ctx, item));
            // 摘掉旧监听再摆状态，避免 RecyclerView 复用时误触发
            h.shown.setOnCheckedChangeListener(null);
            h.follow.setOnCheckedChangeListener(null);
            boolean shown = Boolean.TRUE.equals(shownMap.get(item));
            h.shown.setChecked(shown);
            h.follow.setChecked(Boolean.TRUE.equals(followMap.get(item)));
            h.shown.setEnabled(item.supported());
            // 「跟随」只在「显示」勾上时可配（隐藏 = 功能关闭，不存在「不显示+跟随」态）
            h.follow.setEnabled(item.supported() && shown);
            h.shown.setOnCheckedChangeListener((b, on) -> {
                shownMap.put(item, on);
                // 隐藏只置灰跟随框，不改 followMap：重新勾上显示时原跟随设置自然恢复
                h.follow.setEnabled(on && item.supported());
            });
            h.follow.setOnCheckedChangeListener((b, on) -> followMap.put(item, on));
            h.handle.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && dragStarter != null) {
                    dragStarter.startDrag(h);
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return order.size();
        }

        void move(int from, int to) {
            if (from < 0 || to < 0 || from >= order.size() || to >= order.size() || from == to) {
                return;
            }
            SwitchItem it = order.remove(from);
            order.add(to, it);
            notifyItemMoved(from, to);
        }
    }

    private static String itemTitle(Context ctx, SwitchItem item) {
        switch (item) {
            case USB:
                return ctx.getString(R.string.usb_title);
            case WIRELESS:
                return ctx.getString(R.string.wireless_title);
            case DEV:
                return ctx.getString(R.string.devopts_title);
            case A11Y:
                return ctx.getString(R.string.a11y_title);
            default:
                return item.id;
        }
    }
}