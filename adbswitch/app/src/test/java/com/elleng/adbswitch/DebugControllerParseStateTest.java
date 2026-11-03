package com.elleng.adbswitch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * §3.5 护栏：DebugController.parseState 的纯解析逻辑。
 * 覆盖缺键（返回 null，而非假关）、"null" 值放行、dev 缺失按关、无效值返回 null、噪声行隔离。
 * parseState 已对单测放开为包级可见。仅依赖字符串解析，不碰 Android API。
 */
public class DebugControllerParseStateTest {

    private static final String U = "__ASW_USB_B__";
    private static final String W = "__ASW_WIFI_B__";
    private static final String D = "__ASW_DEV_B__";

    @Test
    public void parsesNormal() {
        // 真实 echo 输出每行一个标记（echo 自带换行），按行定位
        String out = U + "=1\n" + W + "=0\n" + D + "=1";
        DebugController.State s = DebugController.parseState(out, U, W, D);
        assertNotNull(s);
        assertEquals(true, s.usb);
        assertEquals(false, s.wireless);
        assertEquals(true, s.dev);
    }

    @Test
    public void nullValueTreatedAsOff() {
        // settings get 对从未设置的键返回 "null" -> 按关放行（否则整读永久失败）
        String out = U + "=null\n" + W + "=null\n" + D + "=null";
        DebugController.State s = DebugController.parseState(out, U, W, D);
        assertNotNull(s);
        assertEquals(false, s.usb);
        assertEquals(false, s.wireless);
        assertEquals(false, s.dev);
    }

    @Test
    public void missingMarkerLineReturnsNull() {
        // usb 标记行完全缺失 -> 通道异常 -> 返回 null（绝不能把「读不到」当「关」）
        String out = W + "=0;" + D + "=1";
        DebugController.State s = DebugController.parseState(out, U, W, D);
        assertNull(s);
    }

    @Test
    public void devMissingTreatedAsOff() {
        // dev 键缺失按关（个别 ROM 没有这个键，不能因此拖垮整体读取）
        String out = U + "=1\n" + W + "=1";
        DebugController.State s = DebugController.parseState(out, U, W, D);
        assertNotNull(s);
        assertEquals(false, s.dev);
    }

    @Test
    public void invalidValueReturnsNull() {
        // usb 非 0/1/null 视为无效 -> 返回 null
        String out = U + "=x;" + W + "=0;" + D + "=1";
        DebugController.State s = DebugController.parseState(out, U, W, D);
        assertNull(s);
    }

    @Test
    public void ignoresNoiseLines() {
        // shell 掺入噪声行不应干扰按标记行定位
        String out = "unrelated noise line\n" + U + "=1\n" + W + "=1\n" + D + "=0\nmore noise";
        DebugController.State s = DebugController.parseState(out, U, W, D);
        assertNotNull(s);
        assertEquals(true, s.usb);
        assertEquals(true, s.wireless);
        assertEquals(false, s.dev);
    }
}
