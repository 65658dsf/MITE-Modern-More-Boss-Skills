package io.github.ningmeng123.morebossskills.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Add-on settings never modify either prerequisite mod's configuration. @author NingMeng123 */
public final class BossConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue DAMAGE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue COOLDOWN_MULTIPLIER;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("Entities");
        DAMAGE_MULTIPLIER = builder.translation("more_boss_skills.config.boss_skill_damage_multiplier")
                .comment("Additional boss skill damage only; zero keeps telegraphs and control effects.")
                .defineInRange("boss_skill_damage_multiplier", 1D, 0D, 10D);
        COOLDOWN_MULTIPLIER = builder.translation("more_boss_skills.config.boss_skill_cooldown_multiplier")
                .comment("Independent skill cooldown multiplier; never shortens warnings or recovery.")
                .defineInRange("boss_skill_cooldown_multiplier", 1D, .25D, 4D);
        builder.pop();
        SPEC = builder.build();
    }
    public static double number(String key) {
        return switch (key) {
            case "Entities.boss_skill_damage_multiplier" -> DAMAGE_MULTIPLIER.get();
            case "Entities.boss_skill_cooldown_multiplier" -> COOLDOWN_MULTIPLIER.get();
            default -> throw new IllegalArgumentException("Unknown boss setting: " + key);
        };
    }
    private BossConfig() {}
}
