package io.github.ningmeng123.morebossskills.entity.boss;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.ningmeng123.morebossskills.MoreBossSkills;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.modernite.entity.SpawnPopulation;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** /mite boss diagnostics distinguish harmless visual previews from real combat casts. @author NingMeng123 */
@EventBusSubscriber(modid=MoreBossSkills.MOD_ID)
public final class BossSkillCommands {
    private BossSkillCommands() {}

    @SubscribeEvent public static void register(RegisterCommandsEvent event){register(event.getDispatcher());}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mite").requires(source->source.hasPermission(2))
                .then(Commands.literal("boss")
                        .then(Commands.literal("list").executes(context->list(context.getSource())))
                        .then(Commands.literal("stop").executes(context->stop(context.getSource())))
                        .then(Commands.literal("preview")
                                .then(Commands.argument("skill",StringArgumentType.word())
                                        .suggests((context,builder)->SharedSuggestionProvider.suggest(skillIds(),builder))
                                        .executes(context->preview(context.getSource(),parseSkill(StringArgumentType.getString(context,"skill")),null))
                                        .then(Commands.literal("at").then(Commands.argument("position",Vec3Argument.vec3())
                                                .executes(context->preview(context.getSource(),parseSkill(StringArgumentType.getString(context,"skill")),
                                                        Vec3Argument.getVec3(context,"position")))))))
                        .then(Commands.literal("cast")
                                .then(Commands.argument("boss",EntityArgument.entity())
                                        .then(Commands.argument("skill",StringArgumentType.word())
                                                .suggests((context,builder)->SharedSuggestionProvider.suggest(skillIds(),builder))
                                                .then(Commands.argument("target",EntityArgument.player())
                                                        .executes(context->cast(context.getSource(),EntityArgument.getEntity(context,"boss"),
                                                                parseSkill(StringArgumentType.getString(context,"skill")),EntityArgument.getPlayer(context,"target")))))))));
    }

    public static List<String> skillIds(){return Arrays.stream(BossSkill.values()).map(skill->skill.name().toLowerCase(Locale.ROOT)).toList();}

    public static BossSkill parseSkill(String id)throws CommandSyntaxException {
        for(BossSkill skill:BossSkill.values())if(skill.name().equalsIgnoreCase(id))return skill;
        throw error("unknown_skill","Unknown boss skill: %s. Use /mite boss list.",id);
    }

    public static int list(CommandSourceStack source) {
        var message=text("list_header","Boss skills: click an entry to preview it. Previews are visual only.");
        for(BossSkill skill:BossSkill.values()){
            String id=skill.name().toLowerCase(Locale.ROOT);
            message.append("\n").append(Component.literal(id+" — "))
                    .append(Component.translatableWithFallback("skill.more_boss_skills."+id,id)
                            .withStyle(style->style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,"/mite boss preview "+id))));
        }
        source.sendSuccess(()->message,false);return BossSkill.values().length;
    }

    public static int preview(CommandSourceStack source,BossSkill skill,Vec3 requested)throws CommandSyntaxException {
        ServerPlayer player=source.getPlayerOrException();
        if(!BossSkillPreview.connected(player))throw error("no_channel","This client does not support boss skill previews.");
        Vec3 near=requested==null?BossSkillPreview.defaultPosition(player):requested;
        if(!player.level().hasChunkAt(BlockPos.containing(near)))throw error("unloaded","The preview position is in an unloaded chunk.");
        Vec3 ground=BossSkillPreview.ground(player,near).orElseThrow(()->error("no_ground","No local ground at that position. Stand near the floor or choose another position."));
        final int count;
        try{count=BossSkillPreview.start(player,skill,ground);}
        catch(IllegalArgumentException exception){throw error("preview_geometry","The complete preview needs clear, supported ground near that position.");}
        source.sendSuccess(()->text("preview_started","Previewing %s for you only. No damage, movement or terrain changes.",skillName(skill)),false);
        return count;
    }

    public static int stop(CommandSourceStack source)throws CommandSyntaxException {
        ServerPlayer player=source.getPlayerOrException();
        if(!BossSkillPreview.stop(player))throw error("no_channel","This client does not support boss skill previews.");
        source.sendSuccess(()->text("preview_stopped","Your boss skill preview has stopped."),false);return 1;
    }

    public static int cast(CommandSourceStack source,Entity selected,BossSkill skill,ServerPlayer target)throws CommandSyntaxException {
        if(!(selected instanceof Mob boss)||!(boss instanceof ExtremeMob extreme)||!BossAbilities.handles(extreme.extremeKind()))
            throw error("not_boss","Select one of the four supported bosses.");
        if(!boss.isAlive()||boss.isRemoved())throw error("dead_boss","The selected boss is not alive.");
        if(extreme.extremeKind()!=skill.kind)throw error("wrong_boss","%s belongs to %s.",skillName(skill),
                Component.translatableWithFallback("entity.modern_ite."+skill.kind.id(),skill.kind.chinese));
        if(boss.level()!=target.level()||boss.level()!=source.getLevel())throw error("dimension","The command, boss and target must be in the same dimension.");
        if(!target.isAlive()||target.isRemoved()||target.isCreative()||target.isSpectator())
            throw error("invalid_target","The target must be a living survival or adventure player.");
        if(BossAbilities.isBusy(boss))throw error("busy","The boss is already casting or recovering.");
        double range=switch(skill){
            case ZOMBIE_STOMP->8;case ZOMBIE_CHAIN->14;case EMPEROR_SMASH->10;
            case ZOMBIE_MARK,ANNIHILATION_MARK,ANNIHILATION_SINGULARITY,TYRANT_SPIKES->18;
            case ANNIHILATION_SLASH,TYRANT_BLADES,EMPEROR_EDICT->20;
            default->24;
        };
        if(boss.distanceToSqr(target)>range*range)throw error("out_of_range","Move the target within %s blocks of the boss.",(int)range);
        if(skill==BossSkill.TYRANT_COMMAND||skill==BossSkill.EMPEROR_COMMAND){
            boolean skeletons=skill==BossSkill.TYRANT_COMMAND;
            boolean owned=!boss.level().getEntitiesOfClass(Mob.class,boss.getBoundingBox().inflate(24),follower->follower!=boss
                    &&follower.isAlive()&&(!skeletons||follower instanceof AbstractSkeleton)
                    &&follower.getPersistentData().hasUUID(SpawnPopulation.SUMMONER)
                    &&boss.getUUID().equals(follower.getPersistentData().getUUID(SpawnPopulation.SUMMONER))).isEmpty();
            if(!owned)throw error("no_followers","The boss has no eligible existing followers nearby.");
        }
        if(!BossAbilities.forceCast(boss,extreme,skill,target)){
            if(skill==BossSkill.ANNIHILATION_BLINK)throw error("no_landing","There is no safe supported blink destination near the target.");
            throw error("unavailable","The skill cannot start here; check the target's ground and available space.");
        }
        source.sendSuccess(()->text("cast_started","%s is casting %s at %s with normal damage and telegraph timing.",
                boss.getDisplayName(),skillName(skill),target.getDisplayName()),true);
        return 1;
    }

    private static Component skillName(BossSkill skill){
        String id=skill.name().toLowerCase(Locale.ROOT);return Component.translatableWithFallback("skill.more_boss_skills."+id,id);
    }
    private static net.minecraft.network.chat.MutableComponent text(String suffix,String fallback,Object...arguments){
        return Component.translatableWithFallback("commands.more_boss_skills.boss."+suffix,fallback,arguments);
    }
    private static CommandSyntaxException error(String suffix,String fallback,Object...arguments){
        return new SimpleCommandExceptionType(text(suffix,fallback,arguments)).create();
    }
}
