package io.github.ningmeng123.morebossskills.entity.boss;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload.Effect;

/** Deterministic original-sound combinations, shared by audio playback and audits. @author NingMeng123 */
public final class BossSoundTimeline {
    public enum Phase { WARNING, RELEASE, SUSTAIN, FINISH }
    public enum Anchor { ORIGIN, TARGET, MIDPOINT, TRAVEL }
    public record Cue(int tick,Phase phase,String soundId,float volume,float pitch,int lifetime,Anchor anchor) {}
    private static final String[] WARNINGS={"entity.zombie.ambient","block.respawn_anchor.charge",
            "entity.skeleton.ambient","entity.wither.ambient"};
    private static final String[] ACCENTS={"block.heavy_core.hit","block.amethyst_block.chime",
            "block.bone_block.break","entity.wither.shoot"};
    private static final float[] THEME_PITCH={.8F,1.15F,1.05F,.65F};
    private BossSoundTimeline() {}

    /** All ticks are offsets from a production payload's startTick; none precede its warning. */
    public static List<Cue> schedule(Effect effect,int theme,int windup,int duration) {
        if(effect==null||theme<0||theme>3||windup<0||windup>1200||duration<1||duration>2400)
            throw new IllegalArgumentException("Invalid boss sound timeline");
        List<Cue> cues=new ArrayList<>();
        boolean ray=effect==Effect.BEAM||effect==Effect.BLADE||effect==Effect.CHAIN;
        Anchor warningAnchor=ray?Anchor.MIDPOINT:Anchor.ORIGIN;
        if(windup>0){
            add(cues,0,Phase.WARNING,WARNINGS[theme],theme==3?.3F:.42F,THEME_PITCH[theme],Math.min(24,windup),warningAnchor);
            String tension=switch(effect){
                case RING,FAN->"block.deepslate.hit";
                case MARK->"block.amethyst_block.chime";
                case BEAM->"entity.warden.sonic_charge";
                case CHAIN->"block.chain.place";
                case BLINK->"block.portal.ambient";
                case BLADE->"item.trident.return";
                case COMMAND->"entity.illusioner.prepare_mirror";
                case BLACK_HOLE->"block.respawn_anchor.charge";
                case SUN->"block.beacon.activate";
            };
            int warningAt=effect==Effect.BEAM?0:Math.max(0,windup-6);
            add(cues,warningAt,Phase.WARNING,tension,.36F,Math.min(1.8F,THEME_PITCH[theme]+.25F),
                    Math.min(24,windup-warningAt),warningAnchor);
        }
        String release=switch(effect){
            case RING->"entity.iron_golem.attack";
            case MARK,COMMAND->"entity.evoker.cast_spell";
            case BEAM->"entity.warden.sonic_boom";
            case CHAIN->"block.chain.break";
            case BLINK->"entity.enderman.teleport";
            case BLADE->"item.trident.throw";
            case FAN->"entity.player.attack.sweep";
            case BLACK_HOLE->"block.portal.trigger";
            case SUN->"entity.wither.spawn";
        };
        Anchor releaseAnchor=effect==Effect.BLINK?Anchor.TARGET:Anchor.ORIGIN;
        float releaseVolume=effect==Effect.SUN||effect==Effect.BLACK_HOLE?.32F:effect==Effect.BEAM?.5F:.64F;
        add(cues,windup,Phase.RELEASE,release,releaseVolume,THEME_PITCH[theme],Math.min(24,duration),releaseAnchor);
        add(cues,windup,Phase.RELEASE,ACCENTS[theme],.3F,THEME_PITCH[theme],Math.min(12,duration),releaseAnchor);
        String pulse=switch(effect){
            case RING,FAN->"block.deepslate.hit";
            case MARK->"block.amethyst_block.chime";
            case BEAM->"block.amethyst_block.chime";
            case CHAIN->"block.chain.place";
            case BLINK->"block.portal.ambient";
            case BLADE->"item.trident.return";
            case COMMAND->"block.bone_block.hit";
            case BLACK_HOLE->"block.beacon.ambient";
            case SUN->"block.respawn_anchor.ambient";
        };
        int end=windup+duration;
        for(int tick=windup+Math.min(8,Math.max(1,duration/2));tick<end;tick+=16)
            add(cues,tick,Phase.SUSTAIN,pulse,.2F,THEME_PITCH[theme],Math.min(8,end-tick),
                    effect==Effect.BLADE?Anchor.TRAVEL:effect==Effect.BLINK||effect==Effect.CHAIN?Anchor.TARGET:
                            ray?Anchor.MIDPOINT:Anchor.ORIGIN);
        if(effect==Effect.BLACK_HOLE)add(cues,end,Phase.FINISH,"block.respawn_anchor.deplete",.35F,.8F,12,Anchor.ORIGIN);
        if(effect==Effect.SUN)add(cues,end,Phase.FINISH,"block.beacon.deactivate",.3F,.6F,12,Anchor.ORIGIN);
        cues.sort(Comparator.comparingInt(Cue::tick).thenComparing(Cue::phase));
        return List.copyOf(cues);
    }

    private static void add(List<Cue> cues,int tick,Phase phase,String sound,float volume,float pitch,int lifetime,Anchor anchor){
        cues.add(new Cue(tick,phase,"minecraft:"+sound,volume,pitch,Math.max(1,lifetime),anchor));
    }
}
