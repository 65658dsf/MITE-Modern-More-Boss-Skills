package io.github.ningmeng123.morebossskills.client.boss;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Local cosmetic settings never change telegraphs or server hit detection. @author NingMeng123 */
public final class BossVisualConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue QUALITY;
    public static final ModConfigSpec.BooleanValue SCREEN_SHAKE;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("boss_visuals");
        QUALITY = builder.comment("Boss decoration detail: 0 low, 1 balanced, 2 high. Attack boundaries remain visible at every quality.")
                .defineInRange("quality", 1, 0, 2);
        SCREEN_SHAKE = builder.comment("Brief, subtle camera shake for nearby boss impacts; does not move the player or flash the screen.")
                .define("screenShake", true);
        builder.pop();
        SPEC = builder.build();
    }
    private BossVisualConfig() {}
}
