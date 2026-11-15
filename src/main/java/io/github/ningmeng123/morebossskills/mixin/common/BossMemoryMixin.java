package io.github.ningmeng123.morebossskills.mixin.common;

import io.github.ningmeng123.modernite.entity.MobMemory;
import io.github.ningmeng123.morebossskills.entity.boss.BossCombatState;
import io.github.ningmeng123.morebossskills.integration.BossMemoryAccess;
import io.github.ningmeng123.morebossskills.integration.BossStates;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Writes an independent optional compound; empty ordinary mobs remain byte-for-byte unextended. @author NingMeng123 */
@Mixin(value=MobMemory.class,remap=false)
public abstract class BossMemoryMixin implements BossMemoryAccess {
    @Unique private BossCombatState moreBossSkills$combat;
    @Override public BossCombatState moreBossSkills$state(){
        if(moreBossSkills$combat==null)moreBossSkills$combat=new BossCombatState();
        return moreBossSkills$combat;
    }
    @Override public BossCombatState moreBossSkills$peek(){return moreBossSkills$combat;}

    @Inject(method="write",at=@At("TAIL"))
    private void moreBossSkills$write(CompoundTag outer,HolderLookup.Provider registry,CallbackInfo ci){
        if(moreBossSkills$combat==null)return;
        CompoundTag wrapper=new CompoundTag();moreBossSkills$combat.write(wrapper);
        outer.put(BossStates.SAVE_KEY,wrapper.getCompound("BossCombat"));
    }

    @Inject(method="read",at=@At("TAIL"))
    private void moreBossSkills$read(CompoundTag outer,HolderLookup.Provider registry,CallbackInfo ci){
        moreBossSkills$combat=null;
        CompoundTag data=null;
        if(outer.contains(BossStates.SAVE_KEY,Tag.TAG_COMPOUND))data=outer.getCompound(BossStates.SAVE_KEY);
        else{
            CompoundTag legacy=outer.getCompound("ModernIteAbilities").getCompound("BossCombat");
            // The former integrated build wrote empty default state even for ordinary creatures.
            boolean meaningful=legacy.getLong("Serial")>0||legacy.getInt("Phase")>1||legacy.getBoolean("Casting")
                    ||legacy.getInt("EscapeCooldown")>0;
            CompoundTag cooldowns=legacy.getCompound("Cooldowns");
            for(String name:cooldowns.getAllKeys())meaningful|=cooldowns.getInt(name)>0;
            if(meaningful)data=legacy;
        }
        if(data==null)return;
        CompoundTag wrapper=new CompoundTag();wrapper.put("BossCombat",data);
        moreBossSkills$state().read(wrapper);
    }
}
