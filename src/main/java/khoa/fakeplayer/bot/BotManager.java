package khoa.fakeplayer.bot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;

import khoa.fakeplayer.FakePlayerMod;

/** Creates, tracks and removes bots. Server thread only. */
public final class BotManager {
	private static final Map<String, Bot> BOTS = new LinkedHashMap<>();

	private BotManager() {
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	public static Bot get(String name) {
		return BOTS.get(key(name));
	}

	public static Collection<Bot> all() {
		return List.copyOf(BOTS.values());
	}

	public static List<String> names() {
		List<String> n = new ArrayList<>();
		for (Bot b : BOTS.values()) {
			n.add(b.name());
		}
		return n;
	}

	/** Spawns a bot. Returns an error message, or null on success. */
	public static String spawn(MinecraftServer server, ServerLevel level, String name, Vec3 pos, float yaw, float pitch) {
		if (!StringUtil.isValidPlayerName(name)) {
			return "Invalid player name: " + name;
		}
		if (BOTS.containsKey(key(name)) || server.getPlayerList().getPlayerByName(name) != null) {
			return "A player named " + name + " is already online.";
		}
		UUID id = UUIDUtil.createOfflinePlayerUUID(name);
		GameProfile profile = Skins.profile(id, name, BotSettings.get(name));
		ClientInformation info = new ClientInformation("en_us", 2, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true, ParticleStatus.ALL);
		CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, info, false);
		ServerPlayer player = new ServerPlayer(server, level, profile, info);
		BotConnection connection = new BotConnection();
		Bot bot = new Bot(server, name, id, connection, player);
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), FakePlayerMod.LOGGER)) {
			Optional<ValueInput> input = server.getPlayerList().loadPlayerData(new NameAndId(profile))
				.map((CompoundTag tag) -> TagValueInput.create(reporter, server.registryAccess(), tag));
			input.ifPresent(player::load);
			player.snapTo(pos, yaw, pitch);
			server.getPlayerList().placeNewPlayer(connection, player, cookie);
			input.ifPresent(tag -> {
				player.loadAndSpawnEnderPearls(tag);
				player.loadAndSpawnParentVehicle(tag);
			});
		}
		BOTS.put(key(name), bot);
		bot.markLoaded();
		bot.log("spawned at " + pos);
		return null;
	}

	/** Disconnects a bot (player data, including the inventory, is saved by the normal player-leave path). */
	public static boolean disconnect(String name) {
		Bot bot = BOTS.remove(key(name));
		if (bot == null) {
			return false;
		}
		ServerPlayer p = bot.player();
		if (p != null) {
			p.connection.disconnect(Component.literal("Bot disconnected"));
		}
		return true;
	}

	public static void disconnectAll() {
		for (String n : names()) {
			disconnect(n);
		}
	}

	/** Called every server tick. */
	public static void tick() {
		for (Bot b : all()) {
			b.tick();
			if (b.player() == null) {
				BOTS.remove(key(b.name()));
			}
		}
	}
}
