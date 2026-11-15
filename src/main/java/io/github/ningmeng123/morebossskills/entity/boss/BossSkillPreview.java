package io.github.ningmeng123.morebossskills.entity.boss;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload.Effect;

/** Operator-only visual descriptions: packets contain no gameplay actions or spawned entities. @author NingMeng123 */
public final class BossSkillPreview {
    private static final AtomicLong NEXT_ID=new AtomicLong(1L<<50);
    private BossSkillPreview() {}

    public static Vec3 facing(Player player) {
        return BossGeometry.horizontalDirection(Vec3.ZERO,Vec3.directionFromRotation(0,player.getYRot()));
    }

    public static Vec3 defaultPosition(Player player) { return player.position().add(facing(player).scale(6)); }

    public static Optional<Vec3> ground(Player player,Vec3 near) {
        if(!finite(near)||!player.level().hasChunkAt(BlockPos.containing(near)))return Optional.empty();
        return BossGeometry.localFloor(player.level(),player,near);
    }

    public static boolean connected(ServerPlayer player) {
        return player.connection!=null&&player.connection.hasChannel(BossSkillPayload.TYPE);
    }

    /** Build the complete production-shaped timeline; safe for tests because it sends nothing. */
    public static List<BossSkillPayload> describe(Player viewer,BossSkill skill,Vec3 center,Vec3 direction,
                                                 long startTick,long firstCastId) {
        if(skill==null||!finite(center)||!finite(direction))throw new IllegalArgumentException("Invalid preview geometry");
        Vec3 forward=BossGeometry.horizontalDirection(Vec3.ZERO,direction);
        Sequence sequence=new Sequence(viewer,skill,center,startTick,firstCastId);
        Vec3 aim=center.add(forward);
        switch(skill){
            case ZOMBIE_STOMP -> sequence.add(0,Effect.RING,center,aim,20,20,7,.8F,0);
            case ZOMBIE_MARK -> sequence.mark(0,center,28,2.5F);
            case ZOMBIE_CHAIN -> sequence.ray(Effect.CHAIN,forward,1,14,20,6,1.2F);
            case ZOMBIE_TRIPLE -> {
                for(int wave=0;wave<3;wave++)sequence.add(wave*40,Effect.RING,center,aim,20,20,6+wave*2,.8F,90);
            }
            case ANNIHILATION_SLASH -> sequence.ray(Effect.BEAM,forward,.85,20,24,10,2);
            case ANNIHILATION_MARK -> sequence.mark(0,center,26,2.5F);
            case ANNIHILATION_BLINK -> {
                Vec3 destination=ground(viewer,center.add(forward.scale(4.5)))
                        .orElseThrow(()->new IllegalArgumentException("No local floor for the blink preview"));
                sequence.add(0,Effect.BLINK,center,destination,20,20,1,0,0);
                sequence.add(40,Effect.FAN,destination,center,20,8,5,120,0);
            }
            case ANNIHILATION_SINGULARITY -> {
                sequence.add(0,Effect.BLACK_HOLE,center,center,30,60,7,3,0);
                sequence.mark(70,center,20,3);
            }
            case TYRANT_SPIKES -> sequence.mark(0,center,24,2.5F);
            case TYRANT_BLADES -> {
                double angle=Math.atan2(forward.z,forward.x);
                for(int blade=-1;blade<=1;blade++){
                    double theta=angle+Math.toRadians(blade*18);
                    sequence.ray(Effect.BLADE,new Vec3(Math.cos(theta),0,Math.sin(theta)),.8,20,24,30,1.4F);
                }
            }
            case TYRANT_COMMAND,EMPEROR_COMMAND -> sequence.add(0,Effect.COMMAND,center,aim,24,20,16,0,0);
            case TYRANT_TIDE -> {
                for(int wave=0;wave<3;wave++)sequence.add(wave*54,Effect.RING,center,aim,24,30,12,.8F,70);
            }
            case EMPEROR_SMASH -> sequence.add(0,Effect.FAN,center,aim,28,10,9,160,0);
            case EMPEROR_EDICT -> {
                // A stationary preview target demonstrates the same three separately announced edicts.
                for(int mark=0;mark<3;mark++)sequence.mark(mark*20,center,30,2.5F);
            }
            case EMPEROR_SUN -> {
                sequence.add(0,Effect.SUN,center,center,40,108,12,0,0);
                sequence.add(40,Effect.RING,center,aim,24,30,12,.8F,90);
                sequence.add(94,Effect.RING,center,aim,24,30,12,.8F,90);
            }
        }
        return List.copyOf(sequence.packets);
    }

    /** Replace this player's previous preview, with every stage already queued on that client. */
    public static int start(ServerPlayer player,BossSkill skill,Vec3 center) {
        if(!connected(player))return 0;
        List<BossSkillPayload> packets=describe(player,skill,center,facing(player),player.level().getGameTime(),NEXT_ID.getAndAdd(16));
        stop(player);
        for(BossSkillPayload packet:packets)PacketDistributor.sendToPlayer(player,packet);
        return packets.size();
    }

    public static boolean stop(ServerPlayer player) {
        if(!connected(player))return false;
        PacketDistributor.sendToPlayer(player,new BossSkillPayload(player.level().dimension().location(),player.getId(),
                player.getUUID(),-1,Effect.RING,0,player.position(),player.position(),player.level().getGameTime(),0,1,0,0,0,true));
        return true;
    }

    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x)&&Double.isFinite(point.y)&&Double.isFinite(point.z)
                &&Math.abs(point.x)<=3.1E7&&Math.abs(point.y)<=3.1E7&&Math.abs(point.z)<=3.1E7;
    }

    private static final class Sequence {
        final Player viewer;final Vec3 center;final long startTick,firstId;final int theme;
        final List<BossSkillPayload> packets=new ArrayList<>();
        Sequence(Player viewer,BossSkill skill,Vec3 center,long startTick,long firstId){
            this.viewer=viewer;this.center=center;this.startTick=startTick;this.firstId=firstId;
            theme=switch(skill.kind){case ZOMBIE_BOSS->0;case ANNIHILATION_SKELETON->1;case SKELETON_ELITE_LORD->2;default->3;};
        }
        void add(int offset,Effect effect,Vec3 from,Vec3 to,int windup,int duration,float radius,float width,float gap){
            var packet=new BossSkillPayload(viewer.level().dimension().location(),viewer.getId(),viewer.getUUID(),
                    firstId+packets.size(),effect,theme,from,to,startTick+offset,windup,duration,radius,width,gap,false);
            if(!packet.valid())throw new IllegalArgumentException("Invalid preview packet");
            packets.add(packet);
        }
        void mark(int offset,Vec3 point,int windup,float radius){add(offset,Effect.MARK,point,point,windup,12,radius,0,0);}
        void ray(Effect effect,Vec3 direction,double height,double range,int windup,int duration,float width){
            Vec3 from=center.add(0,height,0),wanted=from.add(direction.scale(range));
            for(int step=0;step<=Math.ceil(range);step++)
                if(!viewer.level().hasChunkAt(BlockPos.containing(from.add(direction.scale(Math.min(step,range))))))
                    throw new IllegalArgumentException("A preview ray would enter an unloaded chunk");
            var hit=viewer.level().clip(new ClipContext(from,wanted,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,viewer));
            Vec3 to=hit.getType()==HitResult.Type.MISS?wanted:hit.getLocation();
            add(0,effect,from,to,windup,duration,0,width,0);
        }
    }
}
