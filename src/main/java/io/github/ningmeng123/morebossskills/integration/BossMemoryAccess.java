package io.github.ningmeng123.morebossskills.integration;

import io.github.ningmeng123.morebossskills.entity.boss.BossCombatState;

/** Implemented only while this optional addon is installed. @author NingMeng123 */
public interface BossMemoryAccess {
    BossCombatState moreBossSkills$state();
    BossCombatState moreBossSkills$peek();
}
