package com.lucky.luckyforge.infrastructure.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ObjectKeyBuilder} 单元测试：全覆盖各用途路径拼接。
 * <p>这是最易写错又最影响数据一致性的纯函数，重点覆盖参考图/生成图/打分图/素材包四类用途。
 */
class ObjectKeyBuilderTest {

    @Test
    void 参考图路径格式正确() {
        String key = ObjectKeyBuilder.reference("WALLPAPER", 100L, "a.jpg");
        assertEquals("wallpaper/reference/100/a.jpg", key);
    }

    @Test
    void 原始生成图路径格式正确() {
        String key = ObjectKeyBuilder.raw("WALLPAPER", 5L, 3, "png");
        assertEquals("wallpaper/raw/5/3.png", key);
    }

    @Test
    void 打分图路径格式正确() {
        String key = ObjectKeyBuilder.scored("WALLPAPER", 42L, "png");
        assertEquals("wallpaper/scored/42/42.png", key);
    }

    @Test
    void 素材包图路径格式正确() {
        String key = ObjectKeyBuilder.pkg("WALLPAPER", 9L, 1, "png");
        assertEquals("wallpaper/package/9/1.png", key);
    }

    @Test
    void 垂类统一转小写() {
        String key = ObjectKeyBuilder.reference("AVATAR", 1L, "x.png");
        assertEquals("avatar/reference/1/x.png", key);
    }

    @Test
    void 通用build分层拼接() {
        String key = ObjectKeyBuilder.build("poster", ObjectKeyBuilder.PURPOSE_RAW, "7", "out.png");
        assertEquals("poster/raw/7/out.png", key);
    }
}