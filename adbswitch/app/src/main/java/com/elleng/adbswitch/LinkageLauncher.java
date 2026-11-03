package com.elleng.adbswitch;

/**
 * 快捷打开（联动）的本进程跳转状态机（§3.1 拆分自 MainActivity 的裸 static 标志）。
 *
 * <p>旧实现把 {@code sLinkageLaunched} / {@code sAutoJumped} 两个裸 static 散落在 MainActivity，
 * 生命周期靠注释约定、易出错——曾直接导致 §1.4 的 bug：写入 / 启动失败时这两个标志不回滚，
 * 让本进程内自动跳转永久失效（只能杀进程恢复）。这里把「本进程是否已完成一次自动跳转」
 * 与「上次跳转是否为启动自动跳转来源」收进一个显式状态机，所有读写都经本类方法，
 * 状态转移可单测、不再依赖散落各处的裸赋值。</p>
 *
 * <p>static 保留：进程级状态，保证 Activity 因配置变更重建后仍记得「本次进程内已自动跳转过」，
 * 不会从联动应用返回时又被反复弹回去。</p>
 */
public final class LinkageLauncher {

    /** 本进程内是否已成功自动跳转过：已完成则本次进入主页不再自动跳，避免反复弹回。 */
    private static boolean sLaunched = false;

    /** 上次跳转是否为「启动自动跳转」：决定回到设置页时提示什么（手动跳转不该说「启动时已自动跳转」）。 */
    private static boolean sAutoJumped = false;

    private LinkageLauncher() {
        // 工具类，不可实例化
    }

    /** 本进程内是否已完成过一次自动跳转。 */
    public static boolean hasLaunched() {
        return sLaunched;
    }

    /**
     * 进入主页时查询是否要提示「启动自动跳转」：若上次是自动跳转且尚未消费，
     * 返回 true 并消费掉该标记（只提示一次）。非自动跳转来源的完成不算。
     */
    public static boolean consumeAutoJumpHint() {
        if (sAutoJumped) {
            sAutoJumped = false;
            return true;
        }
        return false;
    }

    /** 联动成功跳转后记录（autoJump=是否为启动自动跳转来源）。 */
    public static void markLaunched(boolean autoJump) {
        sLaunched = true;
        sAutoJumped = autoJump;
    }

    /** 测试用：重置本进程状态。正常进程存活期不重置。 */
    static void resetForTest() {
        sLaunched = false;
        sAutoJumped = false;
    }
}
