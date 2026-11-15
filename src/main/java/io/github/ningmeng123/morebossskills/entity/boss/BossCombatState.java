package io.github.ningmeng123.morebossskills.entity.boss;

import java.util.EnumMap;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Saved cooldowns never resume an invisible half-finished attack after loading. @author NingMeng123 */
public final class BossCombatState {
    public final EnumMap<BossSkill,Integer> cooldowns=new EnumMap<>(BossSkill.class);
    public int phase=1;
    public long serial;
    int globalCooldown=40, recovery, escapeCooldown, stalledTicks, clock;
    int lastHurtAt=-1000,lastSeenAt=-1000,lastMeleeAt=-1000;
    UUID rememberedTarget;
    Vec3 rememberedPosition, progressPosition;
    double progressDistance=Double.MAX_VALUE;
    int selection;
    BossAbilities.Cast active;

    public BossSkill activeSkill() { return active==null?null:active.skill; }
    public int castAge() { return active==null?0:active.age; }
    public int recoveryTicks() { return recovery; }
    public int stalledTicks() { return stalledTicks; }
    public int cooldown(BossSkill skill) { return cooldowns.getOrDefault(skill,0); }

    public void write(CompoundTag outer) {
        CompoundTag state=new CompoundTag(), timers=new CompoundTag();
        cooldowns.forEach((skill,ticks)->timers.putInt(skill.name(),ticks));
        state.put("Cooldowns",timers);state.putInt("Phase",phase);state.putLong("Serial",serial);
        state.putInt("GlobalCooldown",globalCooldown);state.putInt("EscapeCooldown",escapeCooldown);
        state.putBoolean("Casting",active!=null);outer.put("BossCombat",state);
    }

    public void read(CompoundTag outer) {
        CompoundTag state=outer.getCompound("BossCombat"),timers=state.getCompound("Cooldowns");
        cooldowns.clear();
        for(BossSkill skill:BossSkill.values())cooldowns.put(skill,Math.clamp(timers.getInt(skill.name()),0,skill.cooldown*4));
        phase=Math.clamp(state.getInt("Phase"),1,2);serial=Math.max(0,state.getLong("Serial"));
        globalCooldown=Math.max(40,Math.min(700,state.getInt("GlobalCooldown")));
        escapeCooldown=Math.clamp(state.getInt("EscapeCooldown"),0,240);
        active=null;recovery=state.getBoolean("Casting")?20:0;stalledTicks=0;clock=0;
        lastHurtAt=-1000;lastSeenAt=-1000;lastMeleeAt=-1000;
        rememberedTarget=null;rememberedPosition=null;progressPosition=null;progressDistance=Double.MAX_VALUE;
    }
}
