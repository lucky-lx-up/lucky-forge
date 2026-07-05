package com.lucky.luckyforge.infrastructure.storage;

/**
 * 对象路径生成器。
 * <p>按 {@code <vertical>/<purpose>/<bizId>/<filename>} 分层生成 object_key，
 * 路径自带语义、可按前缀筛选/清理、日志可读。上层 MUST 通过本类生成路径，禁止手拼。
 */
public final class ObjectKeyBuilder {

    /** 参考图用途前缀 */
    public static final String PURPOSE_REFERENCE = "reference";
    /** 原始生成图用途前缀 */
    public static final String PURPOSE_RAW = "raw";
    /** 打分图用途前缀 */
    public static final String PURPOSE_SCORED = "scored";
    /** 素材包成品图用途前缀 */
    public static final String PURPOSE_PACKAGE = "package";

    private ObjectKeyBuilder() {
    }

    /**
     * 生成参考图路径：{@code wallpaper/reference/<batchId>/<filename>}
     */
    public static String reference(String vertical, Long batchId, String filename) {
        return build(vertical, PURPOSE_REFERENCE, String.valueOf(batchId), filename);
    }

    /**
     * 生成原始图路径：{@code wallpaper/raw/<runId>/<seq>.png}
     */
    public static String raw(String vertical, Long runId, int seq, String ext) {
        return build(vertical, PURPOSE_RAW, String.valueOf(runId), seq + "." + ext);
    }

    /**
     * 生成打分图路径：{@code wallpaper/scored/<imageId>.png}
     */
    public static String scored(String vertical, Long imageId, String ext) {
        return build(vertical, PURPOSE_SCORED, String.valueOf(imageId), imageId + "." + ext);
    }

    /**
     * 生成素材包图路径：{@code wallpaper/package/<packageId>/<index>.png}
     */
    public static String pkg(String vertical, Long packageId, int index, String ext) {
        return build(vertical, PURPOSE_PACKAGE, String.valueOf(packageId), index + "." + ext);
    }

    /**
     * 通用分层路径拼接。
     *
     * @param vertical 垂类（如 wallpaper）
     * @param purpose  用途（reference/raw/scored/package）
     * @param bizId    业务 ID
     * @param filename 文件名（含扩展名）
     */
    public static String build(String vertical, String purpose, String bizId, String filename) {
        return normalize(vertical) + "/" + purpose + "/" + bizId + "/" + filename;
    }

    /** 垂类统一转小写（枚举名 WALLPAPER -> wallpaper） */
    private static String normalize(String vertical) {
        return vertical == null ? "unknown" : vertical.toLowerCase();
    }
}