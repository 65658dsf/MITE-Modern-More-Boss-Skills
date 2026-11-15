package io.github.ningmeng123.morebossskills.entity;

import io.github.ningmeng123.modernite.entity.*;
import io.github.ningmeng123.morebossskills.integration.BossStates;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkill;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillCommands;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPreview;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Real command dispatch and combat; packet-only preview descriptions cannot mutate the arena. @author NingMeng123 */
@GameTestHolder("more_boss_skills_test")
@PrefixGameTestTemplate(false)
public final class BossSkillCommandsGameTests {
    private BossSkillCommandsGameTests() {}

    @GameTest(template = "gear_empty")
    public static void commandsAreRegisteredWithOperatorPermissionAndAllSkillSuggestions(GameTestHelper h) {
        var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
        var source = h.getLevel().getServer().createCommandSourceStack().withLevel(h.getLevel()).withPermission(2);
        var mite = dispatcher.getRoot().getChild("mite");
        h.assertTrue(mite != null && mite.getChild("boss") != null, "The real server command registration exposes /mite boss");
        for (String child : List.of("list", "preview", "stop", "cast"))
            h.assertTrue(mite.getChild("boss").getChild(child) != null, "The real command tree includes " + child);
        h.assertTrue(execute(h, source, "mite boss list") == 16, "An operator can list all sixteen skills");
        reject(h, source.withPermission(1), "mite boss list", null);
        reject(h, source, "mite boss preview nonexistent_skill", "unknown_skill");
        reject(h, source, "mite boss preview zombie_mark", null);
        reject(h, source, "mite boss stop", null);
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("mite boss preview ", source)).join();
        var names = suggestions.getList().stream().map(com.mojang.brigadier.suggestion.Suggestion::getText).toList();
        h.assertTrue(names.size() == 16 && names.containsAll(BossSkillCommands.skillIds()),
                "Tab completion exposes every accepted skill key through the registered dispatcher");
        h.succeed();
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void castRejectsWrongKindsAndBusyBossesThenUsesTheRealWarningAndDamage(GameTestHelper h) {
        var f = new BossSkillsGameTests.Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        var peer = new Peer(h, f);
        BossSkillsGameTests.ready(h, List.of(f), () -> {
            // Native ServerPlayer login immunity expires on its own tick path, as in melee tests.
            for (int tick = 0; tick < 60; tick++) peer.player.tick();
            peer.player.setPos(f.origin.add(6, 0, 0));
            f.player.setPos(f.origin.add(0, 0, 6));
            peer.player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); peer.player.setHealth(1000);
            var source = peer.player.createCommandSourceStack().withPermission(2);
            String prefix = "mite boss cast " + f.boss.getStringUUID() + " ";
            long terrain = f.terrainFingerprint();
            reject(h, source, prefix + "annihilation_mark @s", "wrong_boss");
            reject(h, source, "mite boss cast @s zombie_mark @s", "not_boss");
            peer.player.setGameMode(GameType.CREATIVE);
            reject(h, source, prefix + "zombie_mark @s", "invalid_target");
            peer.player.setGameMode(GameType.SURVIVAL); peer.player.setPos(f.origin.add(30, 0, 0));
            reject(h, source, prefix + "zombie_mark @s", "out_of_range");
            peer.player.setPos(f.origin.add(6, 0, 0));
            h.assertTrue(f.boss.getTarget() == f.player && !BossAbilities.isBusy(f.boss)
                            && BossStates.of(f.extreme).cooldown(BossSkill.ZOMBIE_MARK) == 0,
                    "Rejected commands leave the current target, scheduler and cooldown untouched");
            h.assertTrue(execute(h, source, prefix + "zombie_mark @s") == 1, "A valid real cast reports one successful start");
            h.assertTrue(BossStates.of(f.extreme).activeSkill() == BossSkill.ZOMBIE_MARK
                            && BossStates.of(f.extreme).cooldown(BossSkill.ZOMBIE_MARK) > 0
                            && f.boss.getTarget() == f.player,
                    "The command schedules the requested cast and its cooldown while preserving the boss's existing AI target");
            h.assertTrue(BossAbilities.castingAim(f.boss) != null
                            && BossAbilities.castingAim(f.boss).distanceToSqr(peer.player.position().add(0, 1, 0)) < 1e-8,
                    "The cast animation faces its separately selected command target rather than the old AI target");
            reject(h, source, prefix + "zombie_stomp @s", "busy");
            h.assertTrue(BossStates.of(f.extreme).activeSkill() == BossSkill.ZOMBIE_MARK,
                    "A busy rejection cannot replace the active skill");
            for (int tick = 0; tick < BossSkill.ZOMBIE_MARK.windupTicks() - 2; tick++) f.step();
            h.assertTrue(peer.player.getHealth() == 1000 && f.hitCount(peer.player) == 0,
                    "The real command preserves the entire harmless warning interval");
            f.finishCast();
            h.assertTrue(BossAbilities.castingAim(f.boss) == null, "Finishing the cast releases its temporary look target");
            h.assertTrue(peer.player.getHealth() < 1000 && f.hitCount(peer.player) == 1 && f.hitCount(f.player) == 0,
                    "After the warning only the separately positioned command target receives one real skill hit");
            h.assertTrue(terrain == f.terrainFingerprint(), "A command cast preserves the complete arena terrain");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void allPreviewDescriptionsAreHarmlessFiniteAndClipTheirLinesAtStone(GameTestHelper h) {
        var f = new BossSkillsGameTests.Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        BossSkillsGameTests.ready(h, List.of(f), () -> {
            long terrain = f.terrainFingerprint();
            Vec3 position = f.player.position(), velocity = f.player.getDeltaMovement();
            float health = f.player.getHealth();
            int entities = h.getLevel().getEntitiesOfClass(Entity.class, f.boss.getBoundingBox().inflate(30)).size();
            var ids = new HashSet<Long>();
            for (BossSkill skill : BossSkill.values()) {
                var packets = BossSkillPreview.describe(f.player, skill, f.origin, new Vec3(1, 0, 0), 1000, 10000L + skill.ordinal() * 16L);
                h.assertTrue(!packets.isEmpty() && packets.size() <= 3, "Every skill has a bounded complete preview: " + skill);
                for (var packet : packets) {
                    h.assertTrue(packet.valid() && !packet.cancelled() && ids.add(packet.castId()), "Preview stages have valid finite geometry and distinct IDs");
                    h.assertTrue(packet.ownerUuid().equals(f.player.getUUID()) && packet.ownerId() == f.player.getId(),
                            "Every preview belongs to its requesting player");
                    h.assertTrue(packet.windup() > 0 && packet.startTick() >= 1000 && packet.startTick() <= 1108,
                            "Each stage retains a positive warning and a bounded scheduled start");
                }
            }
            h.assertTrue(f.player.getHealth() == health && f.player.position().equals(position)
                            && f.player.getDeltaMovement().equals(velocity) && !BossAbilities.isBusy(f.boss),
                    "Describing every preview does not damage, move, pull or start a boss cast");
            h.assertTrue(terrain == f.terrainFingerprint()
                            && h.getLevel().getEntitiesOfClass(Entity.class, f.boss.getBoundingBox().inflate(30)).size() == entities,
                    "Previews neither modify blocks nor spawn gameplay entities");
            f.wall(3, true); long walledTerrain = f.terrainFingerprint();
            for (BossSkill skill : List.of(BossSkill.ZOMBIE_CHAIN, BossSkill.ANNIHILATION_SLASH, BossSkill.TYRANT_BLADES))
                for (var packet : BossSkillPreview.describe(f.player, skill, f.origin, new Vec3(1, 0, 0), 2000, 20000))
                    h.assertTrue(packet.to().x > f.origin.x && packet.to().x < f.origin.x + 3,
                            "Every visible line stops at the actual stone wall: " + skill);
            h.assertTrue(walledTerrain == f.terrainFingerprint(), "Clipping the previews does not break their obstacle");
            boolean rejected = false;
            try { BossSkillPreview.describe(f.player, BossSkill.ZOMBIE_MARK, new Vec3(Double.NaN, 0, 0), Vec3.ZERO, 0, 1); }
            catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected, "Non-finite requested geometry is rejected before creating a packet");
        });
    }

    private static int execute(GameTestHelper h, CommandSourceStack source, String command) {
        try { return h.getLevel().getServer().getCommands().getDispatcher().execute(command, source); }
        catch (CommandSyntaxException failure) { throw new AssertionError("Expected command success: " + command, failure); }
    }

    private static void reject(GameTestHelper h, CommandSourceStack source, String command, String key) {
        try { h.getLevel().getServer().getCommands().getDispatcher().execute(command, source); }
        catch (CommandSyntaxException expected) {
            if (key != null) h.assertTrue(expected.getRawMessage() instanceof Component component
                            && component.getContents() instanceof TranslatableContents contents
                            && contents.getKey().equals("commands.more_boss_skills.boss." + key),
                    "The command rejects for the intended reason " + key + ": " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected command rejection: " + command);
    }

    private static final class Peer {
        final ServerPlayer player;
        final EmbeddedChannel channel;
        Peer(GameTestHelper h, BossSkillsGameTests.Fixture f) {
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "boss-command-test"), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection);
            player.connection = new ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, player, cookie) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, PacketSendListener listener) {}
            };
            player.setGameMode(GameType.SURVIVAL); player.moveTo(f.origin.add(6, 0, 0)); player.setNoGravity(true);
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); player.setHealth(1000);
            f.participants.add(player);
            h.testInfo.addListener(new GameTestListener() {
                @Override public void testStructureLoaded(GameTestInfo info) {}
                @Override public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {}
                @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { channel.finishAndReleaseAll(); }
                @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { channel.finishAndReleaseAll(); }
            });
        }
    }
}
