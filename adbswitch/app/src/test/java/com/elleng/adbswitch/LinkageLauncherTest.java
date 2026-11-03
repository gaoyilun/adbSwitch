package com.elleng.adbswitch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * §3.1 护栏：LinkageLauncher 跳转状态机。
 *
 * 覆盖 §1.4 类 bug 的根因——状态必须由「成功跳转」这一个动作驱动，失败路径绝不置位：
 *  - 未 markLaunched 时 hasLaunched() 为 false；
 *  - markLaunched 后 hasLaunched() 为 true，且只有 autoJump 来源才会给出一次性提示；
 *  - 提示消费后只提示一次，不应重复弹。
 */
public class LinkageLauncherTest {

    @After
    public void tearDown() {
        LinkageLauncher.resetForTest();
    }

    @Test
    public void notLaunchedInitially() {
        assertFalse(LinkageLauncher.hasLaunched());
        assertFalse(LinkageLauncher.consumeAutoJumpHint());
    }

    @Test
    public void manualLaunchMarksButNoHint() {
        LinkageLauncher.markLaunched(false);
        assertTrue(LinkageLauncher.hasLaunched());
        // 手动跳转来源：回来时不该提示「启动时已自动跳转」
        assertFalse(LinkageLauncher.consumeAutoJumpHint());
    }

    @Test
    public void autoJumpHintConsumedOnce() {
        LinkageLauncher.markLaunched(true);
        assertTrue(LinkageLauncher.hasLaunched());
        // 第一次消费给出提示
        assertTrue(LinkageLauncher.consumeAutoJumpHint());
        // 之后只提示一次：已消费，不再给
        assertFalse(LinkageLauncher.consumeAutoJumpHint());
        // 状态仍记得「已跳转过」，不再自动跳
        assertTrue(LinkageLauncher.hasLaunched());
    }

    @Test
    public void hintDoesNotLeakAcrossManualThenAuto() {
        // 先手动跳转（无提示），再自动跳转（应有提示）——标记是覆盖式、与历史无关
        LinkageLauncher.markLaunched(false);
        assertFalse(LinkageLauncher.consumeAutoJumpHint());
        LinkageLauncher.markLaunched(true);
        assertTrue(LinkageLauncher.consumeAutoJumpHint());
    }
}
