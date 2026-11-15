package io.github.ningmeng123.morebossskills.entity.boss;

import io.github.ningmeng123.modernite.entity.ExtremeKind;

/** Fixed skill tuning is independent of a boss's weapon and calendar attack attribute. @author NingMeng123 */
public enum BossSkill {
    ZOMBIE_STOMP(ExtremeKind.ZOMBIE_BOSS,160,20,false),
    ZOMBIE_MARK(ExtremeKind.ZOMBIE_BOSS,200,28,false),
    ZOMBIE_CHAIN(ExtremeKind.ZOMBIE_BOSS,240,20,false),
    ZOMBIE_TRIPLE(ExtremeKind.ZOMBIE_BOSS,560,20,true),
    ANNIHILATION_SLASH(ExtremeKind.ANNIHILATION_SKELETON,160,24,false),
    ANNIHILATION_MARK(ExtremeKind.ANNIHILATION_SKELETON,200,26,false),
    ANNIHILATION_BLINK(ExtremeKind.ANNIHILATION_SKELETON,280,20,false),
    ANNIHILATION_SINGULARITY(ExtremeKind.ANNIHILATION_SKELETON,600,30,true),
    TYRANT_SPIKES(ExtremeKind.SKELETON_ELITE_LORD,160,24,false),
    TYRANT_BLADES(ExtremeKind.SKELETON_ELITE_LORD,200,24,false),
    TYRANT_COMMAND(ExtremeKind.SKELETON_ELITE_LORD,320,24,false),
    TYRANT_TIDE(ExtremeKind.SKELETON_ELITE_LORD,560,24,true),
    EMPEROR_SMASH(ExtremeKind.UNDYING_ZOMBIE_EMPEROR,180,28,false),
    EMPEROR_EDICT(ExtremeKind.UNDYING_ZOMBIE_EMPEROR,240,30,false),
    EMPEROR_COMMAND(ExtremeKind.UNDYING_ZOMBIE_EMPEROR,400,24,false),
    EMPEROR_SUN(ExtremeKind.UNDYING_ZOMBIE_EMPEROR,700,40,true);

    public final ExtremeKind kind;
    public final int cooldown, windup;
    public final boolean ultimate;
    BossSkill(ExtremeKind kind,int cooldown,int windup,boolean ultimate) {
        this.kind=kind;this.cooldown=cooldown;this.windup=windup;this.ultimate=ultimate;
    }
    public int cooldownTicks() { return cooldown; }
    public int windupTicks() { return windup; }
    public boolean ultimate() { return ultimate; }
}
