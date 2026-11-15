package io.github.ningmeng123.morebossskills;

import io.github.ningmeng123.morebossskills.config.BossConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;

/** Optional boss encounters for MITE: Modern ITE. @author NingMeng123 */
@Mod(MoreBossSkills.MOD_ID)
public final class MoreBossSkills {
    public static final String MOD_ID = "more_boss_skills";
    public MoreBossSkills(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, BossConfig.SPEC, "more_boss_skills-common.toml");
        if (FMLEnvironment.dist == Dist.CLIENT)
            container.registerConfig(ModConfig.Type.CLIENT,
                    io.github.ningmeng123.morebossskills.client.boss.BossVisualConfig.SPEC, "more_boss_skills-client.toml");
    }
}
