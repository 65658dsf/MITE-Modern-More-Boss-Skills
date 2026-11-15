package io.github.ningmeng123.morebossskills.client.boss;

import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload.Effect;
import io.github.ningmeng123.morebossskills.entity.boss.BossSoundTimeline;
import io.github.ningmeng123.morebossskills.entity.boss.BossSoundTimeline.Cue;
import io.github.ningmeng123.morebossskills.entity.boss.BossSoundTimeline.Phase;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Owns every audible cue so cancellation also stops already-playing local sounds. @author NingMeng123 */
public final class BossSkillSounds {
    public static final int MAX_VOICES=24,MAX_STARTS_PER_TICK=8,MAX_PULSES_PER_TICK=2,MAX_TRACKED_EFFECTS=128;
    private static final Map<Key,Track> TRACKS=new HashMap<>();
    private static final List<Voice> VOICES=new ArrayList<>();
    private static final Map<DedupeKey,DedupeEntry> RECENT_KEYS=new HashMap<>();
    private static final ArrayDeque<PlayedCue> RECENT_CUES=new ArrayDeque<>();
    private static ClientLevel world;
    private static long budgetTick=Long.MIN_VALUE;
    private static int startsThisTick,pulsesThisTick,peakVoices;
    private static long submitted,stopped,deduplicated,budgetDropped;
    private BossSkillSounds() {}

    public record Diagnostics(long submitted,long stopped,long deduplicated,long budgetDropped,int activeVoices,int trackedEffects,int peakVoices) {}
    public record PlayedCue(UUID owner,long castId,Effect effect,Phase phase,String soundId,long worldTick,
                            float volume,float pitch,Vec3 position) {}
    public static Diagnostics diagnostics(){return new Diagnostics(submitted,stopped,deduplicated,budgetDropped,VOICES.size(),TRACKS.size(),peakVoices);}
    public static List<PlayedCue> recentCues(){return List.copyOf(RECENT_CUES);}
    public static List<SoundInstance> activeSounds(){return VOICES.stream().map(voice->(SoundInstance)voice.sound).toList();}
    public static boolean owns(SoundInstance sound){return sound instanceof OwnedSound;}

    /** Called after visual ownership/death cleanup; does not enqueue a single future audio task. */
    public static void update(List<BossSkillPayload> effects) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null){clear();return;}
        if(world!=mc.level){clear();world=mc.level;}
        if(mc.isPaused())return;
        long now=world.getGameTime();
        if(budgetTick!=now){budgetTick=now;startsThisTick=0;pulsesThisTick=0;}
        Map<Key,BossSkillPayload> current=new LinkedHashMap<>();
        for(BossSkillPayload packet:effects){
            if(current.size()>=MAX_TRACKED_EFFECTS)break;
            if(packet.cancelled()||!packet.valid()||!packet.dimension().equals(world.dimension().location()))continue;
            var owner=world.getEntity(packet.ownerId());
            if(owner==null||owner.isRemoved()||!owner.getUUID().equals(packet.ownerUuid())
                    ||owner instanceof LivingEntity living&&!living.isAlive())continue;
            current.put(new Key(packet.ownerUuid(),packet.ownerId(),packet.castId()),packet);
        }
        for(Iterator<Voice> iterator=VOICES.iterator();iterator.hasNext();){
            Voice voice=iterator.next();
            if(!current.containsKey(voice.key)||now>=voice.until){stopVoice(voice);iterator.remove();}
        }
        TRACKS.keySet().removeIf(key->!current.containsKey(key));
        RECENT_KEYS.entrySet().removeIf(entry->now-entry.getValue().tick>6);
        List<Due> due=new ArrayList<>();
        current.forEach((key,packet)->{
            long age=now-packet.startTick();
            Track track=TRACKS.get(key);
            boolean first=track==null;
            if(first){track=new Track(packet,age-1);TRACKS.put(key,track);}
            if(age<=track.lastAge)return;
            long previousAge=track.lastAge;
            track.lastAge=age;
            if(age<0||age>packet.windup()+packet.duration()+3)return;
            // Normal packet jitter gets a short warning grace; late tracking never replays an old release.
            for(Cue cue:track.cues){
                boolean initialWarning=first&&age<=3&&age<packet.windup()&&cue.phase()==Phase.WARNING&&cue.tick()==0;
                boolean justReached=first?cue.tick()==age:previousAge<cue.tick()&&cue.tick()<=age&&age-cue.tick()<=3;
                if(!initialWarning&&!justReached)continue;
                Vec3 position=position(packet,cue,age);
                if(mc.player.distanceToSqr(position)<=64*64)due.add(new Due(key,packet,cue,position));
            }
        });
        due.sort(Comparator.comparingInt((Due value)->priority(value.cue.phase()))
                .thenComparingDouble(value->mc.player.distanceToSqr(value.position)));
        for(Due value:due)play(value,now);
    }

    /** Stop only this module's sounds for the cancelled owner, including unfinished samples. */
    public static void cancel(UUID owner) {
        for(Iterator<Voice> iterator=VOICES.iterator();iterator.hasNext();){
            Voice voice=iterator.next();
            if(voice.key.owner.equals(owner)){stopVoice(voice);iterator.remove();}
        }
        TRACKS.keySet().removeIf(key->key.owner.equals(owner));
        RECENT_KEYS.entrySet().removeIf(entry->entry.getValue().owner.equals(owner));
    }

    /** Diagnostics are cumulative so an audit can observe the stop even after the world is cleared. */
    public static void clear() {
        for(Voice voice:VOICES)stopVoice(voice);
        VOICES.clear();TRACKS.clear();RECENT_KEYS.clear();world=null;
        budgetTick=Long.MIN_VALUE;startsThisTick=0;pulsesThisTick=0;
    }

    private static void play(Due value,long now) {
        BossSkillPayload packet=value.packet;Cue cue=value.cue;
        // Three fan blades share a source and timing even when their travel/midpoint positions diverge.
        DedupeKey dedupe=new DedupeKey(packet.theme(),cue.phase(),cue.soundId(),
                Math.round(packet.from().x*2),Math.round(packet.from().y*2),Math.round(packet.from().z*2),packet.startTick()+cue.tick());
        if(RECENT_KEYS.containsKey(dedupe)){deduplicated++;return;}
        RECENT_KEYS.put(dedupe,new DedupeEntry(now,packet.ownerUuid()));
        boolean pulse=cue.phase()==Phase.SUSTAIN;
        if(startsThisTick>=MAX_STARTS_PER_TICK||pulse&&pulsesThisTick>=MAX_PULSES_PER_TICK){budgetDropped++;return;}
        if(VOICES.size()>=MAX_VOICES){
            Voice expendable=VOICES.stream().filter(voice->voice.phase==Phase.SUSTAIN).findFirst().orElse(null);
            if(expendable==null||pulse){budgetDropped++;return;}
            stopVoice(expendable);VOICES.remove(expendable);
        }
        OwnedSound sound=new OwnedSound(packet,cue,value.position);
        Minecraft.getInstance().getSoundManager().play(sound);
        int lateness=(int)Math.max(0,now-packet.startTick()-cue.tick());
        VOICES.add(new Voice(value.key,sound,now+Math.max(1,cue.lifetime()-lateness),cue.phase()));
        submitted++;startsThisTick++;if(pulse)pulsesThisTick++;
        peakVoices=Math.max(peakVoices,VOICES.size());
        RECENT_CUES.addLast(new PlayedCue(packet.ownerUuid(),packet.castId(),packet.effect(),cue.phase(),cue.soundId(),now,
                cue.volume(),cue.pitch(),value.position));
        while(RECENT_CUES.size()>128)RECENT_CUES.removeFirst();
    }

    private static int priority(Phase phase){return switch(phase){case RELEASE->0;case WARNING->1;case FINISH->2;case SUSTAIN->3;};}

    private static Vec3 position(BossSkillPayload packet,Cue cue,long age) {
        return switch(cue.anchor()){
            case ORIGIN->packet.from();case TARGET->packet.to();
            case MIDPOINT->packet.from().add(packet.to().subtract(packet.from()).scale(.5));
            case TRAVEL->packet.from().add(packet.to().subtract(packet.from())
                    .scale(Math.clamp((age-packet.windup())/(double)packet.duration(),0,1)));
        };
    }

    private static void stopVoice(Voice voice){Minecraft.getInstance().getSoundManager().stop(voice.sound);stopped++;}

    public static final class OwnedSound extends SimpleSoundInstance {
        private final UUID owner;
        private final long castId;
        private final Effect effect;
        private final Phase phase;
        private OwnedSound(BossSkillPayload packet,Cue cue,Vec3 position){
            super(ResourceLocation.parse(cue.soundId()),SoundSource.HOSTILE,cue.volume(),cue.pitch(),
                    RandomSource.create(packet.castId()^packet.ownerUuid().getLeastSignificantBits()^cue.tick()),
                    false,0,SoundInstance.Attenuation.LINEAR,position.x,position.y,position.z,false);
            owner=packet.ownerUuid();castId=packet.castId();effect=packet.effect();phase=cue.phase();
        }
        public UUID owner(){return owner;}
        public long castId(){return castId;}
        public Effect effect(){return effect;}
        public Phase phase(){return phase;}
    }

    private record Key(UUID owner,int entityId,long castId) {}
    private record Voice(Key key,OwnedSound sound,long until,Phase phase) {}
    private record Due(Key key,BossSkillPayload packet,Cue cue,Vec3 position) {}
    private record DedupeKey(int theme,Phase phase,String sound,long x,long y,long z,long tick) {}
    private record DedupeEntry(long tick,UUID owner) {}
    private static final class Track {
        final List<Cue> cues;long lastAge;
        Track(BossSkillPayload packet,long lastAge){cues=BossSoundTimeline.schedule(packet.effect(),packet.theme(),packet.windup(),packet.duration());this.lastAge=lastAge;}
    }
}
