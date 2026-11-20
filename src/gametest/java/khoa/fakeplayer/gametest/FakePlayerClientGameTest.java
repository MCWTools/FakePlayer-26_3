package khoa.fakeplayer.gametest;

import org.apache.commons.lang3.function.FailableFunction;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import khoa.fakeplayer.api.BotHandle;
import khoa.fakeplayer.bot.Bot;
import khoa.fakeplayer.bot.BotManager;

/**
 * End-to-end check of FakePlayer in a real singleplayer world, run with {@code gradlew runClientGameTest}.
 * The test types commands into the real chat screen, simulates mouse clicks in the invsee GUI, builds a small
 * obstacle course for the path-finder, reads exact server state and takes screenshots (build/run/clientGameTest/screenshots).
 */
public class FakePlayerClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("fakeplayer-gametest");
	private int checks;
	private int failures;

	private void check(boolean ok, String what) {
		if (ok) {
			this.checks++;
			LOGGER.info("ok: {}", what);
		} else {
			this.failures++;
			LOGGER.error("FAILED: {}", what);
		}
	}

	private static <T> T srv(TestSingleplayerContext sp, FailableFunction<MinecraftServer, T, RuntimeException> f) {
		return sp.getServer().computeOnServer(f);
	}

	private static void cmd(ClientGameTestContext c, String command) {
		c.runOnClient(mc -> mc.getConnection().sendCommand(command));
		c.waitTicks(3);
	}

	/** Types the command in the real chat screen like a user. */
	private static void typed(ClientGameTestContext c, String command) {
		c.runOnClient(mc -> mc.gui.openChatScreen(ChatComponent.ChatMethod.COMMAND));
		c.waitTicks(3);
		c.getInput().typeChars(command);
		c.getInput().pressKey(InputConstants.KEY_RETURN);
		c.waitTicks(4);
	}

	private static int count(MinecraftServer s, net.minecraft.world.entity.EntityType<?> type) {
		int n = 0;
		for (Entity e : s.overworld().getAllEntities()) {
			if (e.getType() == type) {
				n++;
			}
		}
		return n;
	}

	private static Bot bot(String name) {
		return BotManager.get(name);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			sp.getConnection().waitForChunksRender();
			context.getInput().resizeWindow(960, 540);
			context.waitTicks(20);

			int gy = srv(sp, s -> s.getPlayerList().getPlayers().get(0).blockPosition().getY()) - 1; // ground block y
			arena(context, gy);

			basics(context, sp, gy);
			inventoryCommands(context, sp);
			mouseActions(context, sp, gy);
			invsee(context, sp);
			pathfinding(context, sp, gy);
			persistenceAndLook(context, sp);
			apiBrain(context, sp, gy);
		}
		LOGGER.info("FakePlayer gametest finished: {} checks ok, {} failed", this.checks, this.failures);
		if (this.failures > 0) {
			throw new AssertionError(this.failures + " checks failed, see log");
		}
	}

	// ------------------------------------------------------------------ arena

	private static void arena(ClientGameTestContext c, int gy) {
		cmd(c, "tp @s 0 " + (gy + 1) + " 0");
		cmd(c, "gamerule mob_griefing false");
		cmd(c, "fill -4 " + (gy + 1) + " -14 30 " + (gy + 8) + " 14 air");
		cmd(c, "fill -4 " + gy + " -14 30 " + gy + " 14 grass_block");
		cmd(c, "kill @e[type=!player]");
	}

	// ------------------------------------------------------------------ basics

	private void basics(ClientGameTestContext c, TestSingleplayerContext sp, int gy) {
		typed(c, "fakeplayer spawn Bot1 2 " + (gy + 1) + " 2");
		check(srv(sp, s -> bot("Bot1") != null && bot("Bot1").isOnline()), "spawn via typed chat command created Bot1");
		check(srv(sp, s -> s.getPlayerList().getPlayers().size()) == 2, "bot is in the real player list");
		check(srv(sp, s -> s.getPlayerList().getPlayerByName("Bot1") != null), "bot found by name like a real player");
		c.waitTicks(10);
		Vec3 p0 = srv(sp, s -> bot("Bot1").position());
		check(p0.y > gy && p0.y < gy + 2, "bot stands on the ground (physics ok): y=" + p0.y);

		// face the bot for a nametag / skin screenshot
		cmd(c, "tp @s 2 " + (gy + 1) + " 5 180 0");
		c.waitTicks(10);
		c.takeScreenshot("fp-1-bot-default");

		cmd(c, "fakeplayer walk Bot1 20");
		c.waitTicks(24);
		Vec3 p1 = srv(sp, s -> bot("Bot1").position());
		double walked = Math.sqrt(Math.pow(p1.x - p0.x, 2) + Math.pow(p1.z - p0.z, 2));
		check(walked > 2.5 && walked < 6.5, "walk 20 ticks moved " + walked + " blocks");
		cmd(c, "fakeplayer sprint Bot1 20");
		c.waitTicks(24);
		Vec3 p2 = srv(sp, s -> bot("Bot1").position());
		double sprinted = Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.z - p1.z, 2));
		check(sprinted > walked * 1.15, "sprint is faster: " + sprinted + " vs walk " + walked);

		cmd(c, "fakeplayer look Bot1 90 0");
		check(Math.abs(srv(sp, s -> bot("Bot1").yaw()) - 90) < 0.5, "look sets yaw");
		cmd(c, "fakeplayer walk Bot1");
		c.waitTicks(15);
		check(srv(sp, s -> bot("Bot1").velocity().lengthSqr()) > 0.001, "walk without duration keeps walking");
		cmd(c, "fakeplayer stopmoving Bot1");
		c.waitTicks(15);
		check(srv(sp, s -> bot("Bot1").velocity().horizontalDistanceSqr()) < 0.0005, "stopmoving stops");

		cmd(c, "fakeplayer lookat Bot1 10.5 " + (gy + 1.5) + " 2.5");
		LOGGER.info("lookat debug: pos={} look={} yaw={}", srv(sp, s -> String.valueOf(bot("Bot1").position())), srv(sp, s -> String.valueOf(bot("Bot1").lookVector())), srv(sp, s -> String.valueOf(bot("Bot1").yaw())));
		check(srv(sp, s -> {
			Vec3 v = bot("Bot1").lookVector();
			Vec3 want = new Vec3(10.5, gy + 1.5, 2.5).subtract(bot("Bot1").cameraPos()).normalize();
			return v.dot(want) > 0.999;
		}), "lookat points the view vector at the target");

		cmd(c, "fakeplayer jump Bot1");
		c.waitTicks(4);
		check(srv(sp, s -> bot("Bot1").position().y) > p2.y + 0.2 || srv(sp, s -> bot("Bot1").velocity().y) > 0, "jump leaves the ground");
		c.waitTicks(20);

		cmd(c, "fakeplayer sneak Bot1 on");
		c.waitTicks(3);
		check(srv(sp, s -> bot("Bot1").isSneaking()), "sneak on");
		cmd(c, "fakeplayer sneak Bot1 off");
		c.waitTicks(3);
		check(!srv(sp, s -> bot("Bot1").isSneaking()), "sneak off");

		cmd(c, "fakeplayer sendchat Bot1 \"hello from bot\"");
		c.waitTicks(5);
		c.takeScreenshot("fp-2-chat");
		cmd(c, "fakeplayer stop Bot1");
	}

	// ------------------------------------------------------------------ inventory

	private static void item(ClientGameTestContext c, String slot, String stack) {
		cmd(c, "item replace entity Bot1 " + slot + " with " + stack);
	}

	private void inventoryCommands(ClientGameTestContext c, TestSingleplayerContext sp) {
		cmd(c, "clear Bot1");
		item(c, "hotbar.0", "diamond_sword");
		item(c, "hotbar.1", "cobblestone 64");
		item(c, "inventory.0", "dirt 10");
		item(c, "inventory.1", "cobblestone 20");
		c.waitTicks(2);
		cmd(c, "fakeplayer hotbarslot Bot1 1");
		check(srv(sp, s -> bot("Bot1").selectedHotbarSlot()) == 1, "hotbarslot selects slot 1");
		check(srv(sp, s -> bot("Bot1").mainHand().is(Items.COBBLESTONE)), "main hand shows the selected slot");

		cmd(c, "fakeplayer throw Bot1 5");
		c.waitTicks(3);
		check(srv(sp, s -> bot("Bot1").item(1).getCount()) == 59, "throw 5 removes exactly 5 from the held stack");
		check(itemEntityTotal(sp) == 5, "five cobblestone items lie on the ground");
		cmd(c, "kill @e[type=item]");
		cmd(c, "fakeplayer throw Bot1 stack");
		c.waitTicks(3);
		check(srv(sp, s -> bot("Bot1").item(1).isEmpty()), "throw stack drops the whole held stack");
		check(itemEntityCount(sp) >= 1, "stack was thrown");
		cmd(c, "kill @e[type=item]");
		item(c, "hotbar.1", "cobblestone 30");
		cmd(c, "fakeplayer throw Bot1 all");
		c.waitTicks(3);
		check(srv(sp, s -> bot("Bot1").item(1).isEmpty() && bot("Bot1").item(10).isEmpty()), "throw all clears every stack of the same item");
		check(itemEntityTotal(sp) == 50, "all 30 + 20 cobblestone were thrown: " + itemEntityTotal(sp));
		cmd(c, "kill @e[type=item]");

		cmd(c, "fakeplayer swaphand Bot1");
		c.waitTicks(2);
		cmd(c, "fakeplayer hotbarslot Bot1 0");
		cmd(c, "fakeplayer swaphand Bot1");
		check(srv(sp, s -> bot("Bot1").offHand().is(Items.DIAMOND_SWORD)), "swaphand moves the held sword to the off hand");
		cmd(c, "fakeplayer swaphand Bot1");
		check(srv(sp, s -> bot("Bot1").mainHand().is(Items.DIAMOND_SWORD)), "swaphand again brings it back");
		cmd(c, "fakeplayer swaphand Bot1 9");
		check(srv(sp, s -> bot("Bot1").item(9).is(Items.DIAMOND_SWORD) && bot("Bot1").mainHand().is(Items.DIRT)), "swaphand <slot> swaps held item with inventory slot 9");
		cmd(c, "fakeplayer swapslot Bot1 9 10");
		check(srv(sp, s -> bot("Bot1").item(10).is(Items.DIAMOND_SWORD)), "swapslot moves slot 9 to 10");
		cmd(c, "fakeplayer swapslot Bot1 10 9");
		cmd(c, "fakeplayer swaphand Bot1 9");
	}

	private static int itemEntityCount(TestSingleplayerContext sp) {
		return srv(sp, s -> count(s, EntityTypes.ITEM));
	}

	private static int itemEntityTotal(TestSingleplayerContext sp) {
		return srv(sp, s -> {
			int n = 0;
			for (Entity e : s.overworld().getAllEntities()) {
				if (e instanceof net.minecraft.world.entity.item.ItemEntity ie) {
					n += ie.getItem().getCount();
				}
			}
			return n;
		});
	}

	// ------------------------------------------------------------------ mouse

	private void mouseActions(ClientGameTestContext c, TestSingleplayerContext sp, int gy) {
		cmd(c, "fakeplayer stop Bot1");
		cmd(c, "tp Bot1 6 " + (gy + 1) + " 8");
		item(c, "hotbar.2", "iron_pickaxe");
		cmd(c, "fakeplayer hotbarslot Bot1 2");
		cmd(c, "setblock 6 " + gy + " 8 stone");
		c.waitTicks(10);
		cmd(c, "fakeplayer look Bot1 0 90");
		cmd(c, "fakeplayer leftmouse Bot1 -1");
		c.waitTicks(30);
		check(srv(sp, s -> s.overworld().getBlockState(new BlockPos(6, gy, 8)).isAir()), "leftmouse -1 with pickaxe mined the block below");
		cmd(c, "fakeplayer stopinteracting Bot1");

		// place: hold cobblestone, look at the ground, single right click
		item(c, "hotbar.3", "cobblestone 16");
		cmd(c, "fakeplayer hotbarslot Bot1 3");
		cmd(c, "setblock 6 " + gy + " 8 grass_block");
		cmd(c, "tp Bot1 6.5 " + (gy + 1) + " 8.5");
		c.waitTicks(5);
		cmd(c, "fakeplayer lookat Bot1 8.5 " + (gy + 0.99) + " 8.5");
		cmd(c, "fakeplayer rightmouse Bot1 0");
		c.waitTicks(6);
		check(srv(sp, s -> s.overworld().getBlockState(new BlockPos(8, gy + 1, 8)).is(net.minecraft.world.level.block.Blocks.COBBLESTONE)), "rightmouse 0 placed a block (one right click)");

		// attack: a pig in front of the bot
		item(c, "hotbar.0", "diamond_sword");
		cmd(c, "fakeplayer hotbarslot Bot1 0");
		cmd(c, "tp Bot1 12 " + (gy + 1) + " 8");
		cmd(c, "summon pig 14 " + (gy + 1) + " 8 {NoAI:1b,Health:6f}");
		c.waitTicks(5);
		cmd(c, "fakeplayer lookat Bot1 14 " + (gy + 1.4) + " 8");
		cmd(c, "fakeplayer leftmouse Bot1 -1");
		c.waitTicks(40);
		check(srv(sp, s -> count(s, EntityTypes.PIG) == 0), "leftmouse -1 attacked and killed the pig");
		cmd(c, "fakeplayer stopinteracting Bot1");

		// middle mouse: pick block
		cmd(c, "setblock 12 " + gy + " 7 diamond_block");
		cmd(c, "fakeplayer lookat Bot1 12.5 " + gy + ".5 7.5");
		cmd(c, "fakeplayer middlemouse Bot1");
		c.waitTicks(3);
		String held = srv(sp, s -> String.valueOf(bot("Bot1").mainHand()));
		LOGGER.info("middlemouse in survival: held {}", held);
		cmd(c, "fakeplayer stop Bot1");
	}

	// ------------------------------------------------------------------ invsee

	private static String itemName(ItemStack s) {
		return s.isEmpty() ? "empty" : s.getItem().toString();
	}

	private void invsee(ClientGameTestContext c, TestSingleplayerContext sp) {
		cmd(c, "clear Bot1");
		item(c, "hotbar.0", "diamond_sword");
		item(c, "hotbar.1", "golden_apple 3");
		item(c, "armor.head", "iron_helmet");
		item(c, "weapon.offhand", "shield");
		item(c, "inventory.0", "emerald 5");
		cmd(c, "fakeplayer hotbarslot Bot1 0");
		cmd(c, "gamemode creative @s");
		cmd(c, "tp @s 0 100 0");   // far away from the bot: invsee must work from anywhere
		c.waitTicks(10);
		typed(c, "fakeplayer invsee Bot1");
		c.waitTicks(8);
		check(c.computeOnClient(mc -> mc.gui.screen() instanceof AbstractContainerScreen<?>), "invsee opened a container screen from far away");
		c.takeScreenshot("fp-3-invsee");
		String layout = c.computeOnClient(mc -> {
			var menu = ((AbstractContainerScreen<?>) mc.gui.screen()).getMenu();
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 45; i++) {
				sb.append(i).append('=').append(itemName(menu.getSlot(i).getItem())).append(' ');
			}
			return sb.toString();
		});
		LOGGER.info("invsee slots: {}", layout);
		check(c.computeOnClient(mc -> {
			var menu = ((AbstractContainerScreen<?>) mc.gui.screen()).getMenu();
			return menu.getSlot(0).getItem().is(Items.IRON_HELMET) && menu.getSlot(4).getItem().is(Items.DIAMOND_SWORD)
				&& menu.getSlot(5).getItem().is(Items.SHIELD) && menu.getSlot(9).getItem().is(Items.EMERALD) && menu.getSlot(37).getItem().is(Items.GOLDEN_APPLE);
		}), "layout: armor row 0 (helmet at 0), mainhand at col 5 (idx 4), offhand at col 6 (idx 5), inventory from idx 9, hotbar row at the bottom");
		check(c.computeOnClient(mc -> !((AbstractContainerScreen<?>) mc.gui.screen()).getMenu().getSlot(36).getItem().is(Items.DIAMOND_SWORD)),
			"selected hotbar cell (36) does not duplicate the item shown in main hand");

		// real mouse clicks: take the emerald (idx 9) and put it into the bot's hotbar slot 5 (idx 41)
		clickSlot(c, 9);
		clickSlot(c, 41);
		c.waitTicks(4);
		check(srv(sp, s -> bot("Bot1").item(5).is(Items.EMERALD) && bot("Bot1").item(9).isEmpty()), "mouse click moved the emerald from inventory to hotbar slot 5");
		// pick the sword from the main hand cell and drop it into inventory idx 10 (slot 10)
		clickSlot(c, 4);
		clickSlot(c, 10);
		c.waitTicks(4);
		check(srv(sp, s -> bot("Bot1").item(10).is(Items.DIAMOND_SWORD) && bot("Bot1").mainHand().isEmpty()), "mouse click took the item out of the main hand cell");
		// put it back into the main hand cell
		clickSlot(c, 10);
		clickSlot(c, 4);
		c.waitTicks(4);
		check(srv(sp, s -> bot("Bot1").mainHand().is(Items.DIAMOND_SWORD)), "mouse click put the sword back into the (empty) main hand cell");
		// armor: take the helmet, place it back
		clickSlot(c, 0);
		LOGGER.info("helmet after pickup: bot={} carried={}", srv(sp, s -> String.valueOf(bot("Bot1").item(39))), c.computeOnClient(mc -> String.valueOf(((AbstractContainerScreen<?>) mc.gui.screen()).getMenu().getCarried())));
		clickSlot(c, 0);
		c.waitTicks(4);
		LOGGER.info("helmet after place: bot={} carried={}", srv(sp, s -> String.valueOf(bot("Bot1").item(39))), c.computeOnClient(mc -> String.valueOf(((AbstractContainerScreen<?>) mc.gui.screen()).getMenu().getCarried())));
		check(srv(sp, s -> bot("Bot1").item(39).is(Items.IRON_HELMET)), "helmet picked up and placed back in the helmet cell");
		// hotbar selection change while the GUI is open follows
		cmd(c, "fakeplayer hotbarslot Bot1 1");
		c.waitTicks(4);
		check(c.computeOnClient(mc -> ((AbstractContainerScreen<?>) mc.gui.screen()).getMenu().getSlot(4).getItem().is(Items.GOLDEN_APPLE)), "main hand cell follows hotbarslot while open");
		c.takeScreenshot("fp-4-invsee-after");
		c.getInput().pressKey(InputConstants.KEY_ESCAPE);
		c.waitTicks(3);
	}

	private static void clickSlot(ClientGameTestContext c, int index) {
		double[] pos = c.computeOnClient(mc -> {
			var screen = (AbstractContainerScreen<?>) mc.gui.screen();
			var slot = screen.getMenu().getSlot(index);
			int imageWidth = 176;
			int imageHeight = 114 + 5 * 18;
			int left = (screen.width - imageWidth) / 2;
			int top = (screen.height - imageHeight) / 2;
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(left + slot.x + 8) * scale, (top + slot.y + 8) * scale};
		});
		c.getInput().setCursorPos(pos[0], pos[1]);
		c.waitTicks(1);
		c.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		c.waitTicks(2);
	}

	// ------------------------------------------------------------------ path-finding

	private void pathfinding(ClientGameTestContext c, TestSingleplayerContext sp, int gy) {
		cmd(c, "fakeplayer stop Bot1");
		// course: start (1,0), wall x=5 (z -12..4), lava lake x 11..13 z -4..4, ledge one block high from x=17
		cmd(c, "tp @s 0 " + (gy + 12) + " 0 -90 60");
		cmd(c, "fill 5 " + (gy + 1) + " -12 5 " + (gy + 3) + " 4 stone");
		cmd(c, "fill 11 " + gy + " -4 13 " + gy + " 4 lava");
		cmd(c, "fill 17 " + (gy + 1) + " -12 24 " + (gy + 1) + " 12 stone");
		cmd(c, "tp Bot1 1 " + (gy + 1) + " 0");
		c.waitTicks(30);
		cmd(c, "fakeplayer debug Bot1 on");
		int goalY = gy + 2;
		cmd(c, "fakeplayer goto Bot1 20 " + goalY + " 0 true");
		c.waitTicks(40);
		c.takeScreenshot("fp-5-path-planned");
		boolean arrived = false;
		boolean touchedLava = false;
		for (int i = 0; i < 70 && !arrived; i++) {
			c.waitTicks(10);
			Vec3 p = srv(sp, s -> bot("Bot1").position());
			touchedLava |= srv(sp, s -> s.overworld().getBlockState(BlockPos.containing(bot("Bot1").position())).getFluidState().is(net.minecraft.tags.FluidTags.LAVA));
			arrived = new Vec3(20.5, goalY, 0.5).distanceTo(p) < 1.6;
		}
		Vec3 end = srv(sp, s -> bot("Bot1").position());
		check(arrived, "bot walked around the wall and lava and up the ledge to the goal, ended at " + end);
		check(!touchedLava, "bot never entered lava");
		check(srv(sp, s -> bot("Bot1").health()) >= 20.0F, "bot took no damage on the way");
		c.takeScreenshot("fp-6-path-arrived");
		cmd(c, "fakeplayer debug Bot1 off");

		// unreachable goal (sealed room): must say so and not wander forever
		cmd(c, "fill 22 " + (gy + 2) + " -3 26 " + (gy + 6) + " 3 stone");
		cmd(c, "fill 23 " + (gy + 2) + " -2 25 " + (gy + 5) + " 2 air");
		cmd(c, "fakeplayer goto Bot1 24 " + (gy + 2) + " 0");
		c.waitTicks(5);
		check(srv(sp, s -> !bot("Bot1").planPath(new BlockPos(24, gy + 2, 0)).found()), "sealed goal is reported unreachable");
		cmd(c, "fakeplayer stop Bot1");
	}

	// ------------------------------------------------------------------ persistence, skin, model

	private void persistenceAndLook(ClientGameTestContext c, TestSingleplayerContext sp) {
		int gy = srv(sp, s -> (int) bot("Bot1").position().y) - 1;
		cmd(c, "clear Bot1");
		item(c, "hotbar.4", "netherite_ingot 7");
		item(c, "armor.chest", "diamond_chestplate");
		cmd(c, "fakeplayer disconnect Bot1");
		c.waitTicks(5);
		check(srv(sp, s -> bot("Bot1") == null && s.getPlayerList().getPlayerByName("Bot1") == null), "disconnect removed the bot");
		typed(c, "fakeplayer spawn Bot1");
		c.waitTicks(10);
		check(srv(sp, s -> bot("Bot1") != null && bot("Bot1").item(4).is(Items.NETHERITE_INGOT) && bot("Bot1").item(4).getCount() == 7), "inventory survived disconnect + reconnect (hotbar)");
		check(srv(sp, s -> bot("Bot1").item(38).is(Items.DIAMOND_CHESTPLATE)), "armor survived disconnect + reconnect");

		cmd(c, "tp @s ~ ~ ~ 0 0");
		Vec3 bp = srv(sp, s -> bot("Bot1").position());
		cmd(c, "tp @s " + bp.x + " " + (bp.y + 0.0) + " " + (bp.z - 3) + " 0 0");
		cmd(c, "fakeplayer look Bot1 180 0");
		cmd(c, "fakeplayer playermodel Bot1 alex");
		c.waitTicks(25);
		check(srv(sp, s -> bot("Bot1") != null && bot("Bot1").item(4).is(Items.NETHERITE_INGOT)), "playermodel respawned the bot and kept its inventory");
		check(srv(sp, s -> {
			var props = bot("Bot1").player().getGameProfile().properties().get("textures");
			return props.stream().anyMatch(p -> new String(java.util.Base64.getDecoder().decode(p.value())).contains("slim"));
		}), "alex model = slim textures property on the profile");
		cmd(c, "tp @s " + bp.x + " " + (bp.y + 0.0) + " " + (bp.z - 3) + " 0 0");
		c.waitTicks(15);
		c.takeScreenshot("fp-7-alex");
		cmd(c, "fakeplayer playermodel Bot1 steve");
		c.waitTicks(25);
		c.takeScreenshot("fp-8-steve");
		cmd(c, "fakeplayer skin Bot1 Notch");
		c.waitTicks(80);
		c.takeScreenshot("fp-9-skin-notch");
		c.waitTicks(200);
		c.takeScreenshot("fp-10-skin-notch-later");
		String skinProps = srv(sp, s -> bot("Bot1").player().getGameProfile().properties().get("textures").toString());
		LOGGER.info("skin props: {}", skinProps);
		cmd(c, "fakeplayer list");
		cmd(c, "fakeplayer info Bot1");
	}

	// ------------------------------------------------------------------ API / scripted AI

	private void apiBrain(ClientGameTestContext c, TestSingleplayerContext sp, int gy) {
		// the "auto hit" example: aim at an entity, if its nearest point is within 3.5 blocks click once
		cmd(c, "kill @e[type=!player]");
		item(c, "hotbar.0", "diamond_sword");
		cmd(c, "fakeplayer hotbarslot Bot1 0");
		cmd(c, "tp Bot1 8 " + (gy + 1) + " -8");
		srv(sp, s -> {
			BotHandle b = bot("Bot1");
			b.setBrain(h -> {
				Entity e = h.aimingEntity();
				if (e != null && h.distanceTo(e, true) <= 3.5 && h.attackStrength() >= 1.0F) {
					h.leftMouse();
				}
			});
			return null;
		});
		cmd(c, "summon zombie 11 " + (gy + 1) + " -8 {NoAI:1b,Health:10f}");
		c.waitTicks(5);
		cmd(c, "fakeplayer lookat Bot1 11 " + (gy + 1.6) + " -8");
		c.waitTicks(60);
		check(srv(sp, s -> count(s, EntityTypes.ZOMBIE) == 0), "brain (aim + distance + leftmouse) killed the zombie through the API");
		srv(sp, s -> {
			bot("Bot1").setBrain(null);
			return null;
		});
		cmd(c, "fakeplayer stop Bot1");

		// knockback: Bot2 hits Bot1, Bot1 must be pushed away
		cmd(c, "tp Bot1 28.5 " + (gy + 1) + " -8.5");
		typed(c, "fakeplayer spawn Bot2 28.5 " + (gy + 1) + " -10.5");
		c.waitTicks(30);
		item(c, "hotbar.0", "diamond_sword");
		cmd(c, "item replace entity Bot2 hotbar.0 with diamond_sword");
		cmd(c, "fakeplayer hotbarslot Bot2 0");
		cmd(c, "fakeplayer lookat Bot2 28.5 " + (gy + 2.4) + " -8.5");
		Vec3 before = srv(sp, s -> bot("Bot1").position());
		cmd(c, "fakeplayer leftmouse Bot2");
		for (int i = 0; i < 6; i++) {
			String st = srv(sp, s -> "z=" + bot("Bot1").position().z + " vel=" + bot("Bot1").velocity() + " hp=" + bot("Bot1").health());
			LOGGER.info("kb tick {}: {}", i, st);
			c.waitTicks(1);
		}
		c.waitTicks(6);
		Vec3 after = srv(sp, s -> bot("Bot1").position());
		check(after.z - before.z > 0.3, "attacked bot got knocked back: moved " + (after.z - before.z));
		cmd(c, "fakeplayer disconnect Bot2");

		// shield held in the off hand while the main hand holds a sword: rightmouse -1 must raise it
		cmd(c, "clear Bot1");
		cmd(c, "item replace entity Bot1 hotbar.0 with diamond_sword");
		cmd(c, "item replace entity Bot1 weapon.offhand with shield");
		cmd(c, "fakeplayer hotbarslot Bot1 0");
		cmd(c, "fakeplayer rightmouse Bot1 -1");
		c.waitTicks(10);
		check(srv(sp, s -> bot("Bot1").isUsingItem() && bot("Bot1").player().isBlocking()), "rightmouse -1 holds the off-hand shield up");
		cmd(c, "fakeplayer stopinteracting Bot1");
		c.waitTicks(5);
		check(srv(sp, s -> !bot("Bot1").isUsingItem()), "stopinteracting lowers the shield");
	}
}
