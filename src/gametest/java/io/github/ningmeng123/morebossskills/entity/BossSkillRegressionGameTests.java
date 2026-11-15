package io.github.ningmeng123.morebossskills.entity;

import io.github.ningmeng123.modernite.entity.*;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Include the existing production-targeting regressions in the focused boss suite. @author NingMeng123 */
@GameTestHolder("more_boss_skills_test")
@PrefixGameTestTemplate(false)
public final class BossSkillRegressionGameTests {
    private BossSkillRegressionGameTests() {}

    @GameTest(template="gear_empty",batch="boss_target_sight")
    public static void aNewHiddenPlayerIsNotDiscoveredByRetargeting(GameTestHelper helper) {
        BossTargetSightGameTests.randomBossRetargetCannotDiscoverAnUnseenPlayerBehindStone(helper);
    }

    @GameTest(template="gear_empty",batch="boss_target_sight")
    public static void aPreviouslySeenPlayerRemainsRememberedAfterHiding(GameTestHelper helper) {
        BossTargetSightGameTests.randomBossRetargetRetainsTheCurrentPlayerAfterTheyHide(helper);
    }
}
