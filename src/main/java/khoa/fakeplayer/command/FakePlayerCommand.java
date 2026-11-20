package khoa.fakeplayer.command;

import java.util.Locale;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import khoa.fakeplayer.api.PathPlan;
import khoa.fakeplayer.FakePlayerMod;
import khoa.fakeplayer.bot.Bot;
import khoa.fakeplayer.bot.BotManager;
import khoa.fakeplayer.bot.BotSettings;
import khoa.fakeplayer.bot.Skins;
import khoa.fakeplayer.inv.InvseeMenu;

/** {@code /fakeplayer ...}: usable by operators and command blocks (permission level 2). */
public final class FakePlayerCommand {
	private static final SuggestionProvider<CommandSourceStack> BOT_NAMES = (ctx, b) -> SharedSuggestionProvider.suggest(BotManager.names(), b);
	private static final SuggestionProvider<CommandSourceStack> THROW_AMOUNT = (ctx, b) ->
		SharedSuggestionProvider.suggest(new String[] {"stack", "all", "1", "2", "4", "8", "16", "32", "64"}, b);
	private static final SimpleCommandExceptionType NO_BOT = new SimpleCommandExceptionType(Component.literal("No such bot is online. Use /fakeplayer spawn <name> first."));

	private FakePlayerCommand() {
	}

	private static LiteralArgumentBuilder<CommandSourceStack> lit(String n) {
		return Commands.literal(n);
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> botArg() {
		return Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES);
	}

	private static Bot bot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Bot b = BotManager.get(StringArgumentType.getString(ctx, "name"));
		if (b == null || !b.isOnline()) {
			throw NO_BOT.create();
		}
		return b;
	}

	private interface Action {
		void run(CommandContext<CommandSourceStack> ctx, Bot bot, CommandSourceStack src) throws CommandSyntaxException;
	}

	private static com.mojang.brigadier.Command<CommandSourceStack> act(String message, Action a) {
		return ctx -> {
			Bot b = bot(ctx);
			a.run(ctx, b, ctx.getSource());
			if (message != null && FakePlayerMod.logs) {
				ctx.getSource().sendSuccess(() -> Component.literal(b.name() + ": " + message), false);
			}
			return 1;
		};
	}

	private static ArgumentBuilder<CommandSourceStack, ?> simple(String name, String message, Action a) {
		return lit(name).then(botArg().executes(act(message, a)));
	}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		LiteralArgumentBuilder<CommandSourceStack> root = lit("fakeplayer").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

		root.then(lit("spawn").then(Commands.argument("name", StringArgumentType.word())
			.executes(ctx -> spawn(ctx, ctx.getSource().getPosition()))
			.then(Commands.argument("pos", Vec3Argument.vec3()).executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "pos"))))));
		root.then(lit("disconnect").then(botArg().executes(ctx -> {
			String n = StringArgumentType.getString(ctx, "name");
			if (!BotManager.disconnect(n)) {
				throw NO_BOT.create();
			}
			ctx.getSource().sendSuccess(() -> Component.literal(n + " disconnected (inventory saved)."), true);
			return 1;
		})));
		root.then(lit("list").executes(ctx -> {
			ctx.getSource().sendSuccess(() -> Component.literal("Bots: " + (BotManager.names().isEmpty() ? "none" : String.join(", ", BotManager.names()))), false);
			return BotManager.names().size();
		}));

		root.then(lit("skin").then(botArg()
			.executes(ctx -> skin(ctx, StringArgumentType.getString(ctx, "name")))
			.then(Commands.argument("account", StringArgumentType.word()).executes(ctx -> skin(ctx, StringArgumentType.getString(ctx, "account"))))));
		root.then(lit("playermodel").then(botArg()
			.then(lit("steve").executes(ctx -> model(ctx, "steve")))
			.then(lit("alex").executes(ctx -> model(ctx, "alex")))));

		// movement
		root.then(lit("walk").then(botArg().executes(act("walking", (c, b, s) -> b.walk(-1)))
			.then(Commands.argument("duration", IntegerArgumentType.integer(1)).executes(act("walking", (c, b, s) -> b.walk(IntegerArgumentType.getInteger(c, "duration")))))));
		root.then(lit("sprint").then(botArg().executes(act("sprinting", (c, b, s) -> b.sprint(-1)))
			.then(Commands.argument("duration", IntegerArgumentType.integer(1)).executes(act("sprinting", (c, b, s) -> b.sprint(IntegerArgumentType.getInteger(c, "duration")))))));
		root.then(lit("jump").then(botArg().executes(act("jump", (c, b, s) -> b.jump(0)))
			.then(Commands.argument("duration", IntegerArgumentType.integer(-1)).executes(act("jumping", (c, b, s) -> b.jump(IntegerArgumentType.getInteger(c, "duration")))))));
		root.then(lit("sneak").then(botArg()
			.then(lit("on").executes(act("sneaking", (c, b, s) -> b.setSneak(true))))
			.then(lit("off").executes(act("not sneaking", (c, b, s) -> b.setSneak(false))))));
		root.then(lit("look").then(botArg().then(Commands.argument("yaw", FloatArgumentType.floatArg())
			.then(Commands.argument("pitch", FloatArgumentType.floatArg()).executes(act("looking", (c, b, s) ->
				b.look(FloatArgumentType.getFloat(c, "yaw"), FloatArgumentType.getFloat(c, "pitch"))))))));
		root.then(lit("lookat").then(botArg()
			.then(Commands.argument("pos", Vec3Argument.vec3()).executes(act("looking at position", (c, b, s) -> b.lookAt(Vec3Argument.getVec3(c, "pos")))))
			.then(lit("entity").then(Commands.argument("target", EntityArgument.entity()).executes(act("looking at entity", (c, b, s) -> {
				Entity e = EntityArgument.getEntity(c, "target");
				b.lookAt(e.getEyePosition());
			}))))));
		root.then(simple("stopmoving", "stopped moving", (c, b, s) -> b.stopMoving()));
		root.then(simple("stopinteracting", "stopped mouse actions", (c, b, s) -> b.stopInteracting()));
		root.then(simple("stop", "stopped everything", (c, b, s) -> b.stopAll()));

		// mouse
		root.then(lit("leftmouse").then(botArg().executes(act("left click", (c, b, s) -> b.leftMouse(0)))
			.then(Commands.argument("duration", IntegerArgumentType.integer(-1)).executes(act("left mouse", (c, b, s) -> b.leftMouse(IntegerArgumentType.getInteger(c, "duration")))))));
		root.then(lit("rightmouse").then(botArg().executes(act("right click", (c, b, s) -> b.rightMouse(0)))
			.then(Commands.argument("duration", IntegerArgumentType.integer(-1)).executes(act("right mouse", (c, b, s) -> b.rightMouse(IntegerArgumentType.getInteger(c, "duration")))))));
		root.then(simple("middlemouse", "middle click", (c, b, s) -> b.middleMouse()));

		// inventory
		root.then(lit("hotbarslot").then(botArg().then(Commands.argument("slot", IntegerArgumentType.integer(0, 8))
			.executes(act("hotbar slot set", (c, b, s) -> b.hotbarSlot(IntegerArgumentType.getInteger(c, "slot")))))));
		root.then(lit("swaphand").then(botArg().executes(act("swapped hands", (c, b, s) -> b.swapHands()))
			.then(Commands.argument("slot", IntegerArgumentType.integer(0, 35)).executes(act("swapped held item with slot", (c, b, s) ->
				b.swapHandWithSlot(IntegerArgumentType.getInteger(c, "slot")))))));
		root.then(lit("swapslot").then(botArg().then(Commands.argument("slot1", IntegerArgumentType.integer(0, 40))
			.then(Commands.argument("slot2", IntegerArgumentType.integer(0, 40)).executes(act("swapped slots", (c, b, s) ->
				b.swapSlots(IntegerArgumentType.getInteger(c, "slot1"), IntegerArgumentType.getInteger(c, "slot2"))))))));
		root.then(lit("throw").then(botArg().then(Commands.argument("amount", StringArgumentType.word()).suggests(THROW_AMOUNT).executes(ctx -> {
			Bot b = bot(ctx);
			String result = b.throwItems(StringArgumentType.getString(ctx, "amount"));
			ctx.getSource().sendSuccess(() -> Component.literal(b.name() + ": " + result), false);
			return 1;
		}))));
		root.then(lit("invsee").then(botArg().executes(ctx -> {
			Bot b = bot(ctx);
			ServerPlayer viewer = ctx.getSource().getPlayerOrException();
			InvseeMenu.open(viewer, b.player());
			return 1;
		})));

		// chat
		for (String alias : new String[] {"sendchat", "sendmessage"}) {
			root.then(lit(alias).then(botArg().then(Commands.argument("message", StringArgumentType.string()).executes(act(null, (c, b, s) ->
				b.chat(StringArgumentType.getString(c, "message")))))));
		}

		// pathfinding
		root.then(lit("goto").then(botArg().then(Commands.argument("pos", Vec3Argument.vec3(false))
			.executes(ctx -> go(ctx, false))
			.then(Commands.argument("sprinting", BoolArgumentType.bool()).executes(ctx -> go(ctx, BoolArgumentType.getBool(ctx, "sprinting")))))));

		root.then(lit("logs").then(Commands.argument("enabled", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(ctx -> {
			FakePlayerMod.logs = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "enabled");
			ctx.getSource().sendSuccess(() -> Component.literal("Bot action messages in chat: " + (FakePlayerMod.logs ? "on" : "off")), false);
			return 1;
		})));
		// debug
		root.then(lit("debug").then(botArg()
			.then(lit("on").executes(act("debug on (actions and path are logged and shown to ops)", (c, b, s) -> b.debug = true)))
			.then(lit("off").executes(act("debug off", (c, b, s) -> b.debug = false)))
			.then(lit("path").executes(ctx -> {
				Bot b = bot(ctx);
				ctx.getSource().sendSuccess(() -> Component.literal(b.name() + " path: " + b.currentPath()), false);
				return 1;
			}))));
		root.then(lit("info").then(botArg().executes(ctx -> {
			Bot b = bot(ctx);
			Vec3 p = b.position();
			Vec3 v = b.velocity();
			String s = String.format(Locale.ROOT, "%s pos(%.2f %.2f %.2f) vel(%.3f %.3f %.3f) yaw %.1f pitch %.1f hp %.1f food %d slot %d held %s ground %b pathing %b",
				b.name(), p.x, p.y, p.z, v.x, v.y, v.z, b.yaw(), b.pitch(), b.health(), b.food(), b.selectedHotbarSlot(), b.mainHand(), b.onGround(), b.isPathing());
			ctx.getSource().sendSuccess(() -> Component.literal(s), false);
			return 1;
		})));

		d.register(root);
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx, Vec3 pos) throws CommandSyntaxException {
		String name = StringArgumentType.getString(ctx, "name");
		CommandSourceStack src = ctx.getSource();
		String err = BotManager.spawn(src.getServer(), src.getLevel(), name, pos, src.getRotation().y, src.getRotation().x);
		if (err != null) {
			throw new SimpleCommandExceptionType(Component.literal(err)).create();
		}
		src.sendSuccess(() -> Component.literal("Spawned " + name + "."), true);
		return 1;
	}

	private static int go(CommandContext<CommandSourceStack> ctx, boolean sprint) throws CommandSyntaxException {
		Bot b = bot(ctx);
		Vec3 v = Vec3Argument.getVec3(ctx, "pos");
		PathPlan plan = b.goTo(BlockPos.containing(v), sprint);
		ctx.getSource().sendSuccess(() -> Component.literal(b.name() + ": " + plan.message()), false);
		return plan.waypoints().isEmpty() ? 0 : 1;
	}

	private static int model(CommandContext<CommandSourceStack> ctx, String model) throws CommandSyntaxException {
		String name = StringArgumentType.getString(ctx, "name");
		UUID id = UUIDUtil.createOfflinePlayerUUID(name);
		BotSettings.Entry e = BotSettings.edit(name);
		Skins.Fetched cur = e.skinValue == null ? null : new Skins.Fetched(e.skinSource, e.skinValue, e.skinSignature);
		Skins.Fetched out = Skins.withModel(cur, id, name, model);
		e.skinValue = out.value();
		e.skinSignature = out.signature();
		e.model = model;
		BotSettings.save();
		return applyProfile(ctx.getSource(), name, "Player model of " + name + " set to " + model + ".");
	}

	private static int skin(CommandContext<CommandSourceStack> ctx, String account) throws CommandSyntaxException {
		String name = StringArgumentType.getString(ctx, "name");
		CommandSourceStack src = ctx.getSource();
		MinecraftServer server = src.getServer();
		src.sendSuccess(() -> Component.literal("Fetching skin of " + account + " ..."), false);
		Skins.fetch(server, account).whenComplete((res, ex) -> server.execute(() -> {
			if (ex != null || res.isEmpty()) {
				src.sendFailure(Component.literal("Could not fetch a skin for account " + account + (ex != null ? " (" + ex.getMessage() + ")" : " (not found or no textures)")));
				return;
			}
			BotSettings.Entry e = BotSettings.edit(name);
			Skins.Fetched f = res.get();
			if (e.model != null) {
				f = Skins.withModel(f, UUIDUtil.createOfflinePlayerUUID(name), name, e.model);
			}
			e.skinValue = f.value();
			e.skinSignature = f.signature();
			e.skinSource = res.get().sourceName();
			BotSettings.save();
			try {
				applyProfile(src, name, "Skin of " + name + " set to " + res.get().sourceName() + ".");
			} catch (CommandSyntaxException ignored) {
				src.sendFailure(Component.literal("Could not apply skin."));
			}
		}));
		return 1;
	}

	/** A profile change needs a fresh player entry, so an online bot is reconnected in place (inventory and position kept). */
	private static int applyProfile(CommandSourceStack src, String name, String message) throws CommandSyntaxException {
		Bot b = BotManager.get(name);
		if (b != null && b.isOnline()) {
			ServerPlayer p = b.player();
			Vec3 pos = p.position();
			float yaw = p.getYRot();
			float pitch = p.getXRot();
			var level = p.level();
			BotManager.disconnect(name);
			String err = BotManager.spawn(src.getServer(), level, name, pos, yaw, pitch);
			if (err != null) {
				throw new SimpleCommandExceptionType(Component.literal(err)).create();
			}
		}
		src.sendSuccess(() -> Component.literal(message), true);
		return 1;
	}
}
