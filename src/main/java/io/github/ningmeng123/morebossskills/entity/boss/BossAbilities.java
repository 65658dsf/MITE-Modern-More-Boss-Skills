package io.github.ningmeng123.morebossskills.entity.boss;

import io.github.ningmeng123.mite.compat.bettercombat.BetterMobCombat;
import io.github.ningmeng123.mite.entity.SourceMeleeGoal;
import io.github.ningmeng123.morebossskills.config.BossConfig;
import io.github.ningmeng123.morebossskills.integration.BossStates;
import io.github.ningmeng123.modernite.entity.ExtremeKind;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.modernite.entity.SpawnPopulation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import static io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload.Effect;

/** Server-authoritative, announced boss attacks. No operation writes a world block. @author NingMeng123 */
public final class BossAbilities {
    public static final ResourceKey<DamageType> DAMAGE_TYPE=ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath("more_boss_skills","boss_skill"));
    private static final int RECOVERY=20, GLOBAL_INTERVAL=40;
    private BossAbilities() {}

    public static boolean handles(ExtremeKind kind) {
        return kind==ExtremeKind.ZOMBIE_BOSS||kind==ExtremeKind.ANNIHILATION_SKELETON
                ||kind==ExtremeKind.SKELETON_ELITE_LORD||kind==ExtremeKind.UNDYING_ZOMBIE_EMPEROR;
    }

    private static BossCombatState state(Mob mob) {
        return mob instanceof ExtremeMob extreme&&handles(extreme.extremeKind())&&extreme.extremeMemory()!=null
                ?BossStates.of(extreme):null;
    }

    public static boolean isBusy(Mob mob) {
        BossCombatState state=state(mob);
        return state!=null&&(state.active!=null||state.recovery>0);
    }

    /** Face the announced attack, including a command's one-off target, without replacing the AI target. */
    public static Vec3 castingAim(Mob mob) {
        BossCombatState state=state(mob);
        return state==null||state.active==null?null:state.active.aim.add(0,1,0);
    }

    public static boolean isSkillDamage(DamageSource source) { return source.is(DAMAGE_TYPE); }

    public static void stop(Mob mob) {
        BossCombatState state=state(mob);
        if(state!=null&&mob.level() instanceof ServerLevel level)cancel(level,mob,state);
    }

    /** A newly tracking player receives the original phase, never a restarted warning. */
    public static void onTracking(ServerPlayer player,Mob mob) {
        BossCombatState state=state(mob);
        if(state==null||state.active==null||!(mob.level() instanceof ServerLevel level)
                ||player.level()!=level||player.connection==null||!player.connection.hasChannel(BossSkillPayload.TYPE))return;
        Cast cast=state.active;
        int theme=theme(((ExtremeMob)mob).extremeKind());
        for(Hazard hazard:cast.hazards){
            int elapsed=cast.age-hazard.createdAt;
            if(elapsed>hazard.windup+hazard.duration)continue;
            var payload=new BossSkillPayload(level.dimension().location(),mob.getId(),mob.getUUID(),hazard.id,
                    hazard.effect,theme,hazard.from,hazard.to,level.getGameTime()-elapsed,hazard.windup,
                    hazard.duration,hazard.radius,hazard.width,hazard.gap,false);
            if(payload.valid())PacketDistributor.sendToPlayer(player,payload);
        }
    }

    public static void onHurt(Mob mob,ExtremeMob extreme,LivingEntity attacker,float damage) {
        if(!handles(extreme.extremeKind())||!(attacker instanceof Player player)||!validPlayer(mob,player)
                ||!(damage>0)||!Float.isFinite(damage))return;
        BossCombatState state=BossStates.of(extreme);
        state.lastHurtAt=state.clock;state.lastSeenAt=state.clock;
        state.rememberedTarget=player.getUUID();state.rememberedPosition=player.position();
    }

    public static void onMeleeHit(Mob mob) {
        BossCombatState state=state(mob);
        if(state!=null){state.lastMeleeAt=state.clock;state.stalledTicks=0;}
    }

    public static void tick(Mob mob,ExtremeMob extreme) {
        if(!handles(extreme.extremeKind())||!(mob.level() instanceof ServerLevel level))return;
        BossCombatState state=BossStates.of(extreme);
        state.clock++;
        state.cooldowns.replaceAll((skill,ticks)->Math.max(0,ticks-1));
        if(state.globalCooldown>0)state.globalCooldown--;
        if(state.escapeCooldown>0)state.escapeCooldown--;
        if(!mob.isAlive()||mob.isRemoved()){cancel(level,mob,state);return;}
        if(mob.getHealth()<=mob.getMaxHealth()*.5F&&state.phase<2){
            state.phase=2;
            for(Player player:players(level,mob.position(),24))
                player.displayClientMessage(Component.translatable("message.more_boss_skills.boss_enraged",mob.getDisplayName()),true);
            mob.playSound(SoundEvents.WITHER_AMBIENT,1,.65F);
        }
        if(state.active!=null){
            tickCast(level,mob,extreme,state);
            return;
        }
        if(state.recovery>0){state.recovery--;suppressMelee(mob);return;}
        if(!(mob.getTarget() instanceof Player target)||!validPlayer(mob,target))return;
        if(mob.getSensing().hasLineOfSight(target)){
            state.lastSeenAt=state.clock;state.rememberedTarget=target.getUUID();state.rememberedPosition=target.position();
        }
        if(!canLocate(mob,state,target))return;
        boolean recentDamage=state.clock-state.lastHurtAt<=100&&target.getUUID().equals(state.rememberedTarget);
        double distance=mob.distanceToSqr(target);
        if(state.progressPosition==null||state.progressPosition.distanceToSqr(mob.position())>=.25
                &&distance<state.progressDistance-.5){
            state.progressPosition=mob.position();state.progressDistance=distance;state.stalledTicks=0;
        }else if(recentDamage&&state.clock-state.lastMeleeAt>20)state.stalledTicks++;
        else if(!recentDamage)state.stalledTicks=0;
        if(state.stalledTicks>=120&&state.escapeCooldown==0&&startEscape(level,mob,extreme,state,target))return;
        if(state.globalCooldown>0)return;
        if(state.stalledTicks>=60){
            BossSkill counter=groundCounter(extreme.extremeKind());
            if(state.cooldown(counter)==0&&begin(level,mob,extreme,state,counter,target))return;
        }
        List<BossSkill> available=new ArrayList<>();
        for(BossSkill skill:BossSkill.values())if(skill.kind==extreme.extremeKind()
                &&state.cooldown(skill)==0&&(!skill.ultimate||state.phase>=2))available.add(skill);
        if(available.isEmpty())return;
        // Rotate eligible skills instead of permanently starving the long-cooldown moves.
        for(int attempt=0;attempt<available.size();attempt++){
            BossSkill skill=available.get(Math.floorMod(state.selection+attempt,available.size()));
            if(begin(level,mob,extreme,state,skill,target)){state.selection++;return;}
        }
    }

    /** Debug/tests enter the same telegraph and impact path, ignoring only readiness and bloodline gates. */
    public static boolean forceCast(Mob mob,ExtremeMob extreme,BossSkill skill,LivingEntity target) {
        if(skill==null||!mob.isAlive()||mob.isRemoved()||!handles(extreme.extremeKind())||skill.kind!=extreme.extremeKind()
                ||!(mob.level() instanceof ServerLevel level)||!(target instanceof Player player)
                ||!validPlayer(mob,player)||isBusy(mob))return false;
        BossCombatState state=BossStates.of(extreme);
        state.rememberedTarget=player.getUUID();state.rememberedPosition=player.position();state.lastSeenAt=state.clock;
        return begin(level,mob,extreme,state,skill,player);
    }

    private static boolean begin(ServerLevel level,Mob mob,ExtremeMob extreme,BossCombatState state,
                                 BossSkill skill,Player target) {
        double distance=mob.distanceToSqr(target);
        double range=switch(skill){
            case ZOMBIE_STOMP -> 8;case ZOMBIE_CHAIN -> 14;case EMPEROR_SMASH -> 10;
            case ZOMBIE_MARK,ANNIHILATION_MARK,ANNIHILATION_SINGULARITY,TYRANT_SPIKES -> 18;
            case ANNIHILATION_SLASH,TYRANT_BLADES,EMPEROR_EDICT -> 20;
            default -> 24;
        };
        if(distance>range*range)return false;
        Vec3 origin=BossGeometry.ground(level,mob,mob.position()).orElse(mob.position());
        Vec3 aim=knownPosition(mob,state,target,target.position());
        Cast cast=new Cast(skill,target.getUUID(),origin,aim);
        if(skill==BossSkill.ANNIHILATION_BLINK){
            cast.destination=blinkLanding(level,mob,target.position());
            if(cast.destination==null)return false;
        }
        if(skill==BossSkill.TYRANT_COMMAND||skill==BossSkill.EMPEROR_COMMAND){
            cast.followers.addAll(followers(level,mob,skill==BossSkill.TYRANT_COMMAND));
            if(cast.followers.isEmpty())return false;
        }
        if(isGroundTargetSkill(skill)&&BossGeometry.ground(level,mob,aim).isEmpty())return false;
        state.active=cast;
        state.cooldowns.put(skill,Math.max(1,(int)Math.round(skill.cooldown*BossConfig.number("Entities.boss_skill_cooldown_multiplier"))));
        BetterMobCombat.cancel(mob);suppressMelee(mob);mob.getNavigation().stop();
        target.displayClientMessage(Component.translatable("skill.more_boss_skills."+skill.name().toLowerCase(Locale.ROOT)),true);
        // The synchronized telegraph supplies positional preparation and release audio on clients.
        schedule(level,mob,extreme,state,cast,target);
        return true;
    }

    private static boolean isGroundTargetSkill(BossSkill skill) {
        return skill==BossSkill.ZOMBIE_MARK||skill==BossSkill.ANNIHILATION_MARK||skill==BossSkill.TYRANT_SPIKES
                ||skill==BossSkill.EMPEROR_EDICT||skill==BossSkill.ANNIHILATION_SINGULARITY;
    }

    private static void tickCast(ServerLevel level,Mob mob,ExtremeMob extreme,BossCombatState state) {
        Cast cast=state.active;
        LivingEntity raw=level.getEntity(cast.target) instanceof LivingEntity living?living:null;
        // Mock players need not be in the world's player list, but the actual target still has its identity.
        if(raw==null&&mob.getTarget()!=null&&mob.getTarget().getUUID().equals(cast.target))raw=mob.getTarget();
        if(!(raw instanceof Player target)||!validPlayer(mob,target)){cancel(level,mob,state);return;}
        suppressMelee(mob);mob.getNavigation().stop();
        mob.setDeltaMovement(0,mob.getDeltaMovement().y,0);
        cast.age++;
        if(cast.escape){
            if(cast.age==20){
                if(BossGeometry.safeLanding(level,mob,cast.destination,target.position())){
                    mob.teleportTo(cast.destination.x,cast.destination.y,cast.destination.z);
                    state.stalledTicks=0;state.progressPosition=mob.position();
                }else {addTargetMark(level,mob,extreme,state,cast,cast.aim,20,2.5F,10);cast.end=52;}
            }
            for(Hazard hazard:cast.hazards)tickHazard(level,mob,cast,hazard);
            if(cast.age>=cast.end)finish(state);
            return;
        }
        schedule(level,mob,extreme,state,cast,target);
        for(Hazard hazard:cast.hazards)tickHazard(level,mob,cast,hazard);
        if(cast.age>=cast.end)finish(state);
    }

    private static void schedule(ServerLevel level,Mob mob,ExtremeMob extreme,BossCombatState state,Cast cast,Player target) {
        BossSkill skill=cast.skill;int age=cast.age;int theme=theme(extreme.extremeKind());
        Vec3 direction=BossGeometry.horizontalDirection(cast.origin,cast.aim);
        if(age==0)switch(skill){
            case ZOMBIE_STOMP -> {add(level,mob,state,cast,Effect.RING,theme,cast.origin,cast.origin.add(direction),20,20,7,.8F,0,10);cast.end=40;}
            case ZOMBIE_MARK -> {addTargetMark(level,mob,extreme,state,cast,cast.aim,28,2.5F,10);cast.end=40;}
            case ZOMBIE_CHAIN -> {
                Vec3 from=mob.position().add(0,1,0),to=from.add(BossGeometry.horizontalDirection(from,cast.aim).scale(14));
                to=BossGeometry.clippedEnd(level,mob,from,to);
                add(level,mob,state,cast,Effect.CHAIN,theme,from,to,20,6,0,1.2F,0,0);cast.end=26;
            }
            case ZOMBIE_TRIPLE -> cast.end=120;
            case ANNIHILATION_SLASH -> {
                Vec3 from=mob.position().add(0,.85,0),to=BossGeometry.clippedEnd(level,mob,from,from.add(direction.scale(20)));
                add(level,mob,state,cast,Effect.BEAM,theme,from,to,24,10,0,2,0,10);cast.end=34;
            }
            case ANNIHILATION_MARK -> {addTargetMark(level,mob,extreme,state,cast,cast.aim,26,2.5F,10);cast.end=38;}
            case ANNIHILATION_BLINK -> {
                add(level,mob,state,cast,Effect.BLINK,theme,cast.origin,cast.destination,20,20,1,0,0,0);cast.end=68;
            }
            case ANNIHILATION_SINGULARITY -> {
                Vec3 point=BossGeometry.ground(level,mob,cast.aim).orElse(cast.aim);
                add(level,mob,state,cast,Effect.BLACK_HOLE,theme,point,point,30,60,7,3,0,0);cast.end=102;
            }
            case TYRANT_SPIKES -> {addTargetMark(level,mob,extreme,state,cast,cast.aim,24,2.5F,10);cast.end=36;}
            case TYRANT_BLADES -> {
                Set<UUID> volleyHits=new HashSet<>();
                for(int blade=-1;blade<=1;blade++){
                    double angle=Math.atan2(direction.z,direction.x)+Math.toRadians(blade*18);
                    Vec3 from=mob.position().add(0,.8,0),to=BossGeometry.clippedEnd(level,mob,from,
                            from.add(Math.cos(angle)*20,0,Math.sin(angle)*20));
                    Hazard hazard=add(level,mob,state,cast,Effect.BLADE,theme,from,to,24,30,0,1.4F,0,10);
                    hazard.hits=volleyHits;
                }cast.end=54;
            }
            case TYRANT_COMMAND,EMPEROR_COMMAND -> {
                add(level,mob,state,cast,Effect.COMMAND,theme,cast.origin,cast.aim,24,20,16,0,0,0);cast.end=skill==BossSkill.TYRANT_COMMAND?68:44;
                for(Mob follower:cast.followers){
                    if(!follower.isAlive())continue;
                    follower.setTarget(target);
                    Vec3 side=BossGeometry.horizontalDirection(follower.position(),target.position());
                    follower.getNavigation().moveTo(target.getX()-side.z*3,target.getY(),target.getZ()+side.x*3,1.2);
                }
            }
            case TYRANT_TIDE -> cast.end=162;
            case EMPEROR_SMASH -> {add(level,mob,state,cast,Effect.FAN,theme,cast.origin,cast.origin.add(direction),28,10,9,160,0,14);cast.end=38;}
            case EMPEROR_EDICT -> cast.end=82;
            case EMPEROR_SUN -> {add(level,mob,state,cast,Effect.SUN,theme,cast.origin,cast.origin,40,108,12,0,0,0);cast.end=148;}
        }
        if(skill==BossSkill.ZOMBIE_TRIPLE&&age%40==0&&age<=80){
            int wave=age/40;
            cast.aim=knownPosition(mob,state,target,cast.aim);
            // A ring's endpoint points into its safe gap, so put that gap behind the announced attack.
            // Capture it once per warning; moving during the warning must remain a valid dodge.
            Vec3 gap=cast.origin.subtract(BossGeometry.horizontalDirection(cast.origin,cast.aim));
            add(level,mob,state,cast,Effect.RING,theme,cast.origin,gap,20,20,6+wave*2,.8F,90,6);
        }
        if(skill==BossSkill.TYRANT_TIDE&&age%54==0&&age<=108)
            add(level,mob,state,cast,Effect.RING,theme,cast.origin,knownPosition(mob,state,target,cast.aim),24,30,12,.8F,70,6);
        if(skill==BossSkill.EMPEROR_SUN&&(age==40||age==94))
            add(level,mob,state,cast,Effect.RING,theme,cast.origin,knownPosition(mob,state,target,cast.aim),24,30,12,.8F,90,6);
        if(skill==BossSkill.EMPEROR_EDICT&&(age==0||age==20||age==40)){
            Vec3 point=knownPosition(mob,state,target,cast.aim);
            addTargetMark(level,mob,extreme,state,cast,point,30,2.5F,10);
        }
        if(skill==BossSkill.ANNIHILATION_BLINK&&age==20){
            if(BossGeometry.safeLanding(level,mob,cast.destination,target.position())){
                mob.teleportTo(cast.destination.x,cast.destination.y,cast.destination.z);cast.origin=cast.destination;
            }
        }
        if(skill==BossSkill.ANNIHILATION_BLINK&&age==40)
            add(level,mob,state,cast,Effect.FAN,theme,cast.origin,knownPosition(mob,state,target,cast.aim),20,8,5,120,0,10);
        if(skill==BossSkill.ANNIHILATION_SINGULARITY&&age==70){
            Vec3 point=cast.hazards.getFirst().from;
            addTargetMark(level,mob,extreme,state,cast,point,20,3,14);
        }
        if((skill==BossSkill.TYRANT_COMMAND||skill==BossSkill.EMPEROR_COMMAND)&&age==24){
            Set<UUID> volleyHits=new HashSet<>();
            for(Mob follower:cast.followers){
                if(!follower.isAlive()||follower.isRemoved()||follower.level()!=level)continue;
                follower.setTarget(target);
                if(skill==BossSkill.EMPEROR_COMMAND)follower.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,120,0),mob);
                else{
                    Vec3 from=follower.position().add(0,1,0);
                    Vec3 to=BossGeometry.clippedEnd(level,mob,from,target.position().add(0,.8,0));
                    Hazard hazard=add(level,mob,state,cast,Effect.BLADE,theme,from,to,20,24,0,1.2F,0,10);
                    hazard.hits=volleyHits;
                    follower.getLookControl().setLookAt(target,30,30);
                    if(follower instanceof AbstractSkeleton skeleton)skeleton.setAggressive(true);
                    follower.startUsingItem(InteractionHand.MAIN_HAND);
                }
            }
        }
        if(skill==BossSkill.TYRANT_COMMAND&&age==44)for(Mob follower:cast.followers){
            if(!follower.isAlive()||follower.isRemoved())continue;
            follower.stopUsingItem();follower.swing(InteractionHand.MAIN_HAND);
            follower.playSound(SoundEvents.SKELETON_SHOOT,1,1);
        }
    }

    private static Hazard add(ServerLevel level,Mob mob,BossCombatState state,Cast cast,Effect effect,int theme,
                              Vec3 from,Vec3 to,int windup,int duration,float radius,float width,float gap,float damage) {
        Hazard hazard=new Hazard(++state.serial,effect,cast.age,from,to,windup,duration,radius,width,gap,damage);
        cast.hazards.add(hazard);
        BossSkillPayload.send(level,mob,hazard.id,effect,theme,from,to,windup,duration,radius,width,gap);
        return hazard;
    }

    private static void addTargetMark(ServerLevel level,Mob mob,ExtremeMob extreme,BossCombatState state,
                                      Cast cast,Vec3 near,int windup,float radius,float damage) {
        BossGeometry.ground(level,mob,near).ifPresent(point->add(level,mob,state,cast,Effect.MARK,
                theme(extreme.extremeKind()),point,point,windup,12,radius,0,0,damage));
    }

    private static void tickHazard(ServerLevel level,Mob mob,Cast cast,Hazard hazard) {
        int elapsed=cast.age-hazard.createdAt-hazard.windup;
        if(elapsed<0||elapsed>hazard.duration)return;
        if(hazard.effect==Effect.CHAIN&&elapsed>0)tickChain(level,hazard,elapsed);
        if(hazard.effect==Effect.BLACK_HOLE){if(elapsed<hazard.duration)pull(level,mob,hazard);return;}
        if(hazard.effect==Effect.BLINK||hazard.effect==Effect.COMMAND||hazard.effect==Effect.SUN)return;
        if(hazard.effect!=Effect.RING&&hazard.effect!=Effect.BLADE&&elapsed!=0)return;
        Vec3 center=hazard.from;
        double reach=Math.max(hazard.radius,hazard.from.distanceTo(hazard.to))+3;
        for(Player victim:players(level,center,reach)){
            if(hazard.hits.contains(victim.getUUID())||!intersects(level,mob,victim,hazard,elapsed))continue;
            // Finite ray attacks cannot hit a target past the wall where the telegraph ends.
            if(hazard.effect==Effect.BEAM||hazard.effect==Effect.BLADE||hazard.effect==Effect.CHAIN){
                Vec3 end=BossGeometry.clippedEnd(level,mob,hazard.from,victim.position().add(0,.8,0));
                if(end.distanceToSqr(victim.position().add(0,.8,0))>.16)continue;
            }
            hazard.hits.add(victim.getUUID());
            if(hazard.effect==Effect.CHAIN){pullChain(level,mob,hazard,victim);continue;}
            float damage=(float)(hazard.damage*BossConfig.number("Entities.boss_skill_damage_multiplier"));
            if(damage<=0)continue;
            DamageSource source=new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(DAMAGE_TYPE),mob);
            if(victim.hurt(source,damage)&&hazard.effect==Effect.MARK)
                victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,20,0),mob);
        }
    }

    private static boolean intersects(ServerLevel level,Mob caster,Player victim,Hazard hazard,int elapsed) {
        Vec3 feet=victim.position();
        if(hazard.effect==Effect.RING){
            // A jump over the shallow wave is a real dodge, not merely a visual animation.
            Vec3 floor=BossGeometry.localFloor(level,caster,new Vec3(feet.x,hazard.from.y,feet.z)).orElse(null);
            if(floor==null||feet.y-floor.y>.65||feet.y-floor.y<-.6)return false;
            double radius=hazard.radius*Math.clamp(elapsed/(double)hazard.duration,0,1);
            return BossGeometry.inRing(new Vec3(feet.x,hazard.from.y,feet.z),hazard.from,BossGeometry.horizontalDirection(hazard.from,hazard.to),
                    radius,hazard.width+victim.getBbWidth(),hazard.gap);
        }
        if(hazard.effect==Effect.MARK){
            Vec3 floor=BossGeometry.localFloor(level,caster,new Vec3(feet.x,hazard.from.y,feet.z)).orElse(null);
            return floor!=null&&Math.abs(feet.y-floor.y)<=1.25
                    &&BossGeometry.horizontalDistanceSquared(feet,hazard.from)<=Math.pow(hazard.radius+victim.getBbWidth()*.5,2);
        }
        if(hazard.effect==Effect.FAN){
            Vec3 floor=BossGeometry.localFloor(level,caster,new Vec3(feet.x,hazard.from.y,feet.z)).orElse(null);
            if(floor==null||Math.abs(feet.y-floor.y)>1.25||BossGeometry.horizontalDistanceSquared(feet,hazard.from)>hazard.radius*hazard.radius)return false;
            Vec3 facing=BossGeometry.horizontalDirection(hazard.from,hazard.to),toward=BossGeometry.horizontalDirection(hazard.from,feet);
            return toward.dot(facing)>=Math.cos(Math.toRadians(hazard.width*.5));
        }
        if(hazard.effect==Effect.BLADE){
            double previous=Math.clamp((elapsed-1)/(double)hazard.duration,0,1),current=Math.clamp(elapsed/(double)hazard.duration,0,1);
            Vec3 delta=hazard.to.subtract(hazard.from),from=hazard.from.add(delta.scale(previous)),to=hazard.from.add(delta.scale(current));
            return Math.abs(feet.y+.8-from.y)<1.2&&BossGeometry.distanceToSegment(feet,from,to)<=hazard.width*.5+victim.getBbWidth()*.5;
        }
        return Math.abs(feet.y+.8-hazard.from.y)<1.5
                &&BossGeometry.distanceToSegment(feet,hazard.from,hazard.to)<=hazard.width*.5+victim.getBbWidth()*.5;
    }

    private static void pullChain(ServerLevel level,Mob mob,Hazard hazard,Player victim) {
        Vec3 direction=BossGeometry.horizontalDirection(victim.position(),mob.position());
        // Impulse is collision-resolved by ordinary player movement; no positional teleport through blocks.
        Vec3 motion=direction.scale(.4);
        if(level.noCollision(victim,victim.getBoundingBox().move(motion))) {
            victim.setDeltaMovement(motion.x,victim.getDeltaMovement().y,motion.z);victim.hurtMarked=true;
            hazard.pulls.put(victim.getUUID(),new ChainPull(victim,victim.position(),direction));
        }
    }

    private static void tickChain(ServerLevel level,Hazard hazard,int elapsed) {
        for(ChainPull pull:hazard.pulls.values()){
            Player victim=pull.victim;
            if(victim.isRemoved()||!victim.isAlive())continue;
            double moved=Math.sqrt(BossGeometry.horizontalDistanceSquared(victim.position(),pull.origin));
            double remaining=Math.max(0,3-moved);
            if(elapsed>=hazard.duration||remaining<=.05){stopPull(pull);continue;}
            Vec3 motion=pull.direction.scale(Math.min(.4,remaining));
            if(!level.noCollision(victim,victim.getBoundingBox().move(motion))){stopPull(pull);continue;}
            victim.setDeltaMovement(motion.x,victim.getDeltaMovement().y,motion.z);victim.hurtMarked=true;
        }
    }

    private static void stopPull(ChainPull pull) {
        Player victim=pull.victim;
        Vec3 motion=victim.getDeltaMovement();double inward=motion.dot(pull.direction);
        if(inward>0){victim.setDeltaMovement(motion.subtract(pull.direction.scale(inward)));victim.hurtMarked=true;}
    }

    private static void pull(ServerLevel level,Mob mob,Hazard hazard) {
        for(Player victim:players(level,hazard.from,hazard.radius)){
            if(Math.abs(victim.getY()-hazard.from.y)>2||BossGeometry.horizontalDistanceSquared(victim.position(),hazard.from)>hazard.radius*hazard.radius)continue;
            Vec3 center=hazard.from.add(0,.8,0),feet=victim.position().add(0,.8,0);
            if(BossGeometry.clippedEnd(level,mob,center,feet).distanceToSqr(feet)>.1)continue;
            Vec3 inward=BossGeometry.horizontalDirection(victim.position(),hazard.from);
            Vec3 velocity=victim.getDeltaMovement();
            double component=velocity.dot(inward);
            double addition=Math.clamp(.22-component,0,.06);
            Vec3 next=velocity.add(inward.scale(addition));
            if(addition>0&&level.noCollision(victim,victim.getBoundingBox().move(inward.scale(addition)))){
                victim.setDeltaMovement(next);victim.hurtMarked=true;
            }
        }
    }

    private static boolean startEscape(ServerLevel level,Mob mob,ExtremeMob extreme,BossCombatState state,Player target) {
        Vec3 destination=BossGeometry.escapeLanding(level,mob,target.position()).orElse(null);
        state.escapeCooldown=240;
        if(destination==null){
            BossSkill counter=groundCounter(extreme.extremeKind());
            return state.cooldown(counter)==0&&begin(level,mob,extreme,state,counter,target);
        }
        Cast cast=new Cast(null,target.getUUID(),mob.position(),target.position());
        cast.escape=true;cast.destination=destination;cast.end=20;state.active=cast;
        BetterMobCombat.cancel(mob);suppressMelee(mob);
        add(level,mob,state,cast,Effect.BLINK,theme(extreme.extremeKind()),mob.position(),destination,20,20,1,0,0,0);
        return true;
    }

    private static Vec3 blinkLanding(ServerLevel level,Mob mob,Vec3 target) {
        double start=Math.atan2(mob.getZ()-target.z,mob.getX()-target.x);
        for(double radius:new double[]{4.5,5.5})for(int i=0;i<16;i++){
            double angle=start+Math.PI+i*Math.PI/8;
            Vec3 near=new Vec3(target.x+Math.cos(angle)*radius,target.y,target.z+Math.sin(angle)*radius);
            Vec3 point=BossGeometry.ground(level,mob,near).orElse(null);
            if(point!=null&&BossGeometry.safeLanding(level,mob,point,target))return point;
        }
        return null;
    }

    private static List<Mob> followers(ServerLevel level,Mob mob,boolean skeletons) {
        return level.getEntitiesOfClass(Mob.class,mob.getBoundingBox().inflate(24),candidate->candidate!=mob
                &&candidate.isAlive()&&(!skeletons||candidate instanceof AbstractSkeleton)
                &&candidate.getPersistentData().hasUUID(SpawnPopulation.SUMMONER)
                &&mob.getUUID().equals(candidate.getPersistentData().getUUID(SpawnPopulation.SUMMONER)))
                .stream().sorted(java.util.Comparator.comparingDouble(mob::distanceToSqr)).limit(6).toList();
    }

    private static List<Player> players(ServerLevel level,Vec3 center,double range) {
        return level.getEntitiesOfClass(Player.class,new AABB(center,center).inflate(range,4,range),
                player->player.isAlive()&&!player.isRemoved()&&!player.isCreative()&&!player.isSpectator());
    }

    private static boolean validPlayer(Mob mob,Player player) {
        return player.isAlive()&&!player.isRemoved()&&!player.isCreative()&&!player.isSpectator()
                &&player.level()==mob.level()&&mob.distanceToSqr(player)<=32*32;
    }

    private static boolean canLocate(Mob mob,BossCombatState state,Player target) {
        return mob.getSensing().hasLineOfSight(target)||target.getUUID().equals(state.rememberedTarget)
                &&state.clock-state.lastSeenAt<=100;
    }

    private static Vec3 knownPosition(Mob mob,BossCombatState state,Player target,Vec3 fallback) {
        if(mob.getSensing().hasLineOfSight(target))return target.position();
        return target.getUUID().equals(state.rememberedTarget)&&state.clock-state.lastSeenAt<=100
                &&state.rememberedPosition!=null?state.rememberedPosition:fallback;
    }

    private static BossSkill groundCounter(ExtremeKind kind) {
        return switch(kind){
            case ZOMBIE_BOSS -> BossSkill.ZOMBIE_MARK;
            case ANNIHILATION_SKELETON -> BossSkill.ANNIHILATION_MARK;
            case SKELETON_ELITE_LORD -> BossSkill.TYRANT_SPIKES;
            default -> BossSkill.EMPEROR_EDICT;
        };
    }

    private static int theme(ExtremeKind kind) {
        return switch(kind){case ZOMBIE_BOSS->0;case ANNIHILATION_SKELETON->1;case SKELETON_ELITE_LORD->2;default->3;};
    }

    private static void suppressMelee(Mob mob) {
        if(mob instanceof PathfinderMob pathfinder){var melee=SourceMeleeGoal.first(pathfinder);if(melee!=null)melee.suppress(3);}
    }

    private static void finish(BossCombatState state) {
        clearCast(state.active);
        state.active=null;state.recovery=RECOVERY;state.globalCooldown=GLOBAL_INTERVAL+RECOVERY;
    }

    private static void cancel(ServerLevel level,Mob mob,BossCombatState state) {
        if(state.active!=null)BossSkillPayload.sendCancel(level,mob);
        clearCast(state.active);
        state.active=null;state.recovery=RECOVERY;state.globalCooldown=GLOBAL_INTERVAL+RECOVERY;
    }

    private static void clearCast(Cast cast) {
        if(cast==null)return;
        for(Hazard hazard:cast.hazards)hazard.pulls.values().forEach(BossAbilities::stopPull);
        if(cast.skill==BossSkill.TYRANT_COMMAND)for(Mob follower:cast.followers)follower.stopUsingItem();
    }

    static final class Cast {
        final BossSkill skill;final UUID target;
        Vec3 origin,destination,aim;
        int age,end;boolean escape;
        final List<Hazard> hazards=new ArrayList<>();
        final List<Mob> followers=new ArrayList<>();
        Cast(BossSkill skill,UUID target,Vec3 origin,Vec3 aim){this.skill=skill;this.target=target;this.origin=origin;this.aim=aim;}
    }

    private static final class Hazard {
        final long id;
        final Effect effect;final int createdAt,windup,duration;
        final Vec3 from,to;final float radius,width,gap,damage;
        Set<UUID> hits=new HashSet<>();
        final Map<UUID,ChainPull> pulls=new HashMap<>();
        Hazard(long id,Effect effect,int createdAt,Vec3 from,Vec3 to,int windup,int duration,float radius,float width,float gap,float damage){
            this.id=id;this.effect=effect;this.createdAt=createdAt;this.from=from;this.to=to;this.windup=windup;this.duration=duration;
            this.radius=radius;this.width=width;this.gap=gap;this.damage=damage;
        }
    }

    private record ChainPull(Player victim,Vec3 origin,Vec3 direction) {}
}
