package io.github.ningmeng123.morebossskills.entity;

import io.github.ningmeng123.modernite.entity.ExtremeKind;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.modernite.entity.ExtremeMobs;
import io.github.ningmeng123.modernite.entity.MobMemory;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkill;
import io.github.ningmeng123.morebossskills.integration.BossStates;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The optional extension saves its own state through unmodified base entity serialization. @author NingMeng123 */
@GameTestHolder("more_boss_skills_test")
@PrefixGameTestTemplate(false)
public final class BossAddonPersistenceGameTests {
    private static final String STATE_KEY = "more_boss_skills:combat";
    private BossAddonPersistenceGameTests() {}

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void nativeEntitySavesPreserveAddonCooldownsAndSafelyMigrateTheOldEmbeddedFormat(GameTestHelper h) {
        var fixture = new BossSkillsGameTests.Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        BossSkillsGameTests.ready(h, List.of(fixture), () -> {
            fixture.extreme.extremeMemory().cooldown = 37;
            fixture.extreme.extremeMemory().contribution.put(fixture.player.getUUID(), 12F);
            fixture.cast(BossSkill.ZOMBIE_MARK);
            int cooldown = BossStates.of(fixture.extreme).cooldown(BossSkill.ZOMBIE_MARK);
            CompoundTag saved = fixture.boss.saveWithoutId(new CompoundTag());
            h.assertTrue(saved.contains(STATE_KEY, Tag.TAG_COMPOUND), "The actual entity save includes the separately namespaced add-on state");
            h.assertFalse(saved.getCompound("ModernIteAbilities").contains("BossCombat"), "New saves do not insert extension fields into the base memory compound");
            var loaded = ExtremeMobs.type(ExtremeKind.ZOMBIE_BOSS).create(h.getLevel());
            h.assertTrue(loaded != null, "The base registered boss can be recreated");
            try {
                loaded.load(saved);
                var memory = ((ExtremeMob) loaded).extremeMemory(); var state = BossStates.of((ExtremeMob) loaded);
                h.assertTrue(state.cooldown(BossSkill.ZOMBIE_MARK) == cooldown && state.activeSkill() == null && state.recoveryTicks() > 0,
                        "Native loading retains cooldowns but safely cancels the pending attack into recovery");
                h.assertTrue(memory.cooldown == 37 && memory.contribution.getOrDefault(fixture.player.getUUID(), 0F) == 12F,
                        "The extension's serialization preserves the base challenge and player contribution");
                CompoundTag legacy = saved.copy();
                legacy.getCompound("ModernIteAbilities").put("BossCombat", legacy.getCompound(STATE_KEY).copy()); legacy.remove(STATE_KEY);
                loaded.load(legacy);
                h.assertTrue(BossStates.of((ExtremeMob) loaded).cooldown(BossSkill.ZOMBIE_MARK) == cooldown,
                        "A world saved by the earlier embedded implementation keeps its existing skill cooldown");
                CompoundTag migrated = loaded.saveWithoutId(new CompoundTag());
                h.assertTrue(migrated.contains(STATE_KEY, Tag.TAG_COMPOUND)
                                && !migrated.getCompound("ModernIteAbilities").contains("BossCombat"),
                        "The next native save migrates valid old data to the extension's own key");
            } finally { loaded.discard(); }
        });
    }

    @GameTest(template = "gear_empty")
    public static void unusedBaseMemoryAndUnrelatedBaseMobsDoNotGainAnAddonSaveTag(GameTestHelper h) {
        var memory = new MobMemory(); memory.cooldown = 19; memory.stage = 3;
        CompoundTag plain = new CompoundTag(); memory.write(plain, h.getLevel().registryAccess());
        h.assertFalse(plain.contains(STATE_KEY), "An untouched base memory has no allocated or serialized boss extension state");
        var unrelated = ExtremeMobs.type(ExtremeKind.ZOMBIE_CALLER).create(h.getLevel());
        h.assertTrue(unrelated != null, "An unrelated base mob creates without add-on intervention");
        try {
            CompoundTag saved = unrelated.saveWithoutId(new CompoundTag());
            h.assertFalse(saved.contains(STATE_KEY), "Saving a non-boss base mob does not introduce a boss-skill tag");
        } finally { unrelated.discard(); }
        plain.getCompound("ModernIteAbilities").put("BossCombat", new CompoundTag());
        var reloaded = new MobMemory(); reloaded.read(plain, h.getLevel().registryAccess());
        CompoundTag rewritten = new CompoundTag(); reloaded.write(rewritten, h.getLevel().registryAccess());
        h.assertFalse(rewritten.contains(STATE_KEY), "An empty obsolete boss compound is ignored instead of allocating extension state");
        h.assertTrue(reloaded.cooldown == 19 && reloaded.stage == 3, "Ignoring an empty legacy tag does not lose base memory fields");
        h.succeed();
    }
}
