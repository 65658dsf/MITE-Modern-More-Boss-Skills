package io.github.ningmeng123.morebossskills.integration;

import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.modernite.entity.MobMemory;
import io.github.ningmeng123.morebossskills.entity.boss.BossCombatState;
import java.util.Objects;

/** Lazy addon state; the original ITE memory class has no source dependency on this addon. @author NingMeng123 */
public final class BossStates {
    public static final String SAVE_KEY="more_boss_skills:combat";
    private BossStates() {}
    public static BossCombatState of(ExtremeMob mob){return of(Objects.requireNonNull(mob).extremeMemory());}
    public static BossCombatState of(MobMemory memory){
        Object raw=Objects.requireNonNull(memory);
        if(raw instanceof BossMemoryAccess access)return access.moreBossSkills$state();
        throw new IllegalStateException("More Boss Skills memory mixin was not applied");
    }
    public static BossCombatState existing(MobMemory memory){
        return (Object)memory instanceof BossMemoryAccess access?access.moreBossSkills$peek():null;
    }
}
