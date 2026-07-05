package com.lucky.luckyforge.infrastructure.persistence.enums;

/**
 * 垂类（对应 vertical 字段注释枚举）。
 * <p>WALLPAPER 壁纸（首版）/ AVATAR 头像（预留）/ POSTER 海报（预留）。
 */
public enum Vertical {
    WALLPAPER,
    AVATAR,
    POSTER;

    public String value() {
        return name();
    }
}