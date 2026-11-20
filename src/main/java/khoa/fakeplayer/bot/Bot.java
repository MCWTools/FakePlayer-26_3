package khoa.fakeplayer.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.HashedStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import khoa.fakeplayer.FakePlayerMod;
import khoa.fakeplayer.api.BotBrain;
import khoa.fakeplayer.api.BotHandle;
import khoa.fakeplayer.api.PathPlan;
import khoa.fakeplayer.path.Pathfinder;

/**
 * One fake player. All actions are performed the way a client would: server packet handlers (input, sprint,
 * attack, use item, block breaking, container clicks, hotbar selection) are driven through the bot's own
 * connection. The bot has no client, so it also simulates the movement a client would normally compute.
 */
public final class Bot implements BotHandle {
	private static final class Mouse {
		int remaining; // 0 idle, -1 until stopped, >0 ticks left
		boolean click;

		boolean active() {
			return this.remaining != 0 || this.click;
		}

		void tickDown() {
			this.click = false;
			if (this.remaining > 0) {
				this.remaining--;
			}
		}
	}

	private final MinecraftServer server;
	private final String name;
	private final java.util.UUID id;
	private final BotConnection connection;
	private ServerPlayer player;

	private boolean fwd, back, left, right, jump, sneak, sprintKey;
	private int walkTicks;
	private int sprintTicks;
	private int jumpTicks;
	private final Mouse leftM = new Mouse();
	private final Mouse rightM = new Mouse();
	private int rightDelay;
	private Input lastInput = Input.EMPTY;
	private int sequence;
	private int deathTicks;
	private BotBrain brain;
	private final List<Consumer<String>> chatListeners = new ArrayList<>();
	private final List<String> chatLog = new ArrayList<>();

	// block breaking
	private BlockPos breakPos;
	private float breakProgress;
	private int breakDelay;

	// path following
	private List<Pathfinder.Node> path = List.of();
	private int pathIndex;
	private BlockPos pathGoal;
	private boolean pathSprint;
	private int pathReplans;
	private int stuckTicks;
	private Vec3 stuckRef = Vec3.ZERO;
	private int pathChain;

	public boolean debug;

	Bot(MinecraftServer server, String name, java.util.UUID id, BotConnection connection, ServerPlayer player) {
		this.server = server;
		this.name = name;
		this.id = id;
		this.connection = connection;
		this.player = player;
		connection.setSniffer(p -> {
			if (BotConnection.isChat(p) && p instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket c) {
				String text = c.content().getString();
				this.chatLog.add(text);
				if (this.chatLog.size() > 50) {
					this.chatLog.remove(0);
				}
				for (Consumer<String> l : this.chatListeners) {
					l.accept(text);
				}
			}
		});
	}

	void log(String msg) {
		if (this.debug) {
			FakePlayerMod.LOGGER.info("[bot {}] {}", this.name, msg);
			if (FakePlayerMod.logs) {
				debugChat("[bot " + this.name + "] " + msg);
			}
		}
	}

	private void debugChat(String msg) {
		for (ServerPlayer p : this.server.getPlayerList().getPlayers()) {
			if (p != this.player && BotManager.get(p.getGameProfile().name()) == null && p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
				p.sendSystemMessage(Component.literal(msg), true);
			}
		}
	}

	// ---------------------------------------------------------------- packets

	/** Delivers a serverbound packet exactly like the network layer would. */
	@SuppressWarnings("unchecked")
	void sendPacket(Packet<? extends ServerGamePacketListener> packet) {
		if (this.player == null) {
			return;
		}
		if (this.debug) {
			this.log("-> " + packet.getClass().getSimpleName());
		}
		((Packet<ServerGamePacketListener>) packet).handle(this.player.connection);
	}

	private int nextSeq() {
		return ++this.sequence;
	}

	void markLoaded() {
		this.sendPacket(new ServerboundPlayerLoadedPacket());
	}

	// ---------------------------------------------------------------- tick

	/** Called once per server tick by {@link BotManager}. */
	void tick() {
		ServerPlayer current = this.server.getPlayerList().getPlayer(this.id);
		if (current == null) {
			this.player = null;
			return;
		}
		this.player = current;
		this.connection.setSelfId(this.player.getId());
		net.minecraft.world.phys.Vec3 push;
		while ((push = this.connection.pollMotion()) != null) {
			this.player.lerpMotion(push);
		}
		this.player.connection.resetPosition();
		Packet<? super ServerGamePacketListener> reply;
		int replies = 0;
		while (replies++ < 8 && (reply = this.connection.pollReply()) != null) {
			this.sendPacket((Packet<? extends ServerGamePacketListener>) reply);
		}
		if (this.player.isDeadOrDying()) {
			this.releaseAll();
			if (++this.deathTicks >= 20) {
				this.deathTicks = 0;
				this.log("respawning");
				this.sendPacket(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
				this.markLoaded();
			}
			return;
		}
		this.deathTicks = 0;

		if (this.brain != null) {
			this.brain.tick(this);
		}
		this.tickPath();
		this.applyMovement();
		this.tickMouse();
		this.player.doTick();
		if (this.player.tickCount % 10 == 0) {
			this.player.level().getChunkSource().move(this.player);
		}
	}

	private void releaseAll() {
		this.fwd = this.back = this.left = this.right = this.jump = this.sneak = this.sprintKey = false;
		this.walkTicks = this.sprintTicks = this.jumpTicks = 0;
		this.leftM.remaining = this.rightM.remaining = 0;
		this.path = List.of();
	}

	// ---------------------------------------------------------------- movement

	private void applyMovement() {
		boolean f = this.fwd;
		if (this.walkTicks != 0) {
			f = true;
			if (this.walkTicks > 0 && --this.walkTicks == 0) {
				this.fwd = false;
				f = this.fwd;
			}
		}
		boolean sp = this.sprintKey;
		if (this.sprintTicks != 0) {
			f = true;
			sp = true;
			if (this.sprintTicks > 0 && --this.sprintTicks == 0) {
				sp = this.sprintKey;
			}
		}
		boolean j = this.jump;
		if (this.jumpTicks != 0) {
			j = true;
			if (this.jumpTicks > 0) {
				this.jumpTicks--;
			}
		}
		Input in = new Input(f, this.back, this.left, this.right, j, this.sneak, sp && f);
		if (!in.equals(this.lastInput)) {
			this.sendPacket(new ServerboundPlayerInputPacket(in));
			this.lastInput = in;
		}
		ServerPlayer p = this.player;
		boolean wantSprint = in.sprint() && p.getFoodData().getFoodLevel() > 6 && !p.isUsingItem() && !this.sneak;
		if (wantSprint && p.horizontalCollision && !p.minorHorizontalCollision) {
			wantSprint = false;
		}
		if (wantSprint != p.isSprinting()) {
			this.sendPacket(new ServerboundPlayerCommandPacket(p, wantSprint
				? ServerboundPlayerCommandPacket.Action.START_SPRINTING : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
		}

		float forward = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
		float strafe = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
		float len = Mth.sqrt(forward * forward + strafe * strafe);
		if (len > 0) {
			float scale = 1.0F / len;
			if (p.isUsingItem() && !p.isPassenger()) {
				scale *= p.getUseItem().getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT).speedMultiplier();
			}
			if (p.isShiftKeyDown() || p.isVisuallyCrawling()) {
				scale *= (float) p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED);
			}
			forward *= scale;
			strafe *= scale;
		}
		p.zza = forward;
		p.xxa = strafe;
		p.setJumping(in.jump());
	}

	// ---------------------------------------------------------------- looking

	@Override
	public void look(float yaw, float pitch) {
		if (this.player != null) {
			this.player.absSnapRotationTo(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0F, 90.0F));
			this.player.setYHeadRot(this.player.getYRot());
		}
	}

	@Override
	public void lookAt(Vec3 target) {
		if (this.player == null) {
			return;
		}
		Vec3 d = target.subtract(this.player.getEyePosition());
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = (float) (Mth.atan2(d.z, d.x) * 180.0 / Math.PI) - 90.0F;
		float pitch = (float) -(Mth.atan2(d.y, h) * 180.0 / Math.PI);
		this.look(yaw, pitch);
	}

	// ---------------------------------------------------------------- mouse

	private HitResult hit() {
		return this.aimingAt();
	}

	@Override
	public HitResult aimingAt() {
		ServerPlayer p = this.player;
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1.0F);
		double blockReach = p.blockInteractionRange();
		double entityReach = p.entityInteractionRange();
		BlockHitResult blk = p.level().clip(new ClipContext(eye, eye.add(look.scale(blockReach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		double blockDist = blk.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : blk.getLocation().distanceTo(eye);
		Vec3 end = eye.add(look.scale(entityReach));
		AABB box = p.getBoundingBox().expandTowards(look.scale(entityReach)).inflate(1.0);
		EntityHitResult ent = ProjectileUtil.getEntityHitResult(p, eye, end, box, e -> !e.isSpectator() && e.isPickable(), entityReach * entityReach);
		if (ent != null && ent.getLocation().distanceTo(eye) < blockDist) {
			return ent;
		}
		return blk.getType() == HitResult.Type.MISS ? BlockHitResult.miss(end, Direction.getApproximateNearest(look), BlockPos.containing(end)) : blk;
	}

	private void tickMouse() {
		ServerPlayer p = this.player;
		if (this.leftM.active() && !p.isUsingItem()) {
			this.leftTick(this.leftM.click);
		} else {
			this.abortBreaking();
		}
		this.leftM.tickDown();
		if (this.rightDelay > 0) {
			this.rightDelay--;
		}
		if (this.rightM.active()) {
			this.rightTick();
		} else if (p.isUsingItem() && this.rightWasHolding) {
			this.sendPacket(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
			this.rightWasHolding = false;
		}
		this.rightM.tickDown();
	}

	private boolean rightWasHolding;

	private void leftTick(boolean click) {
		ServerPlayer p = this.player;
		HitResult h = this.hit();
		if (h instanceof EntityHitResult eh) {
			this.abortBreaking();
			if (click || p.getAttackStrengthScale(0.5F) >= 1.0F) {
				this.sendPacket(new ServerboundAttackPacket(eh.getEntity().getId()));
				this.sendPacket(ServerboundPunchPacket.INSTANCE);
			}
		} else if (h instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
			this.mine(bh, click);
		} else {
			this.abortBreaking();
			if (click) {
				this.sendPacket(ServerboundPunchPacket.INSTANCE);
			}
		}
	}

	private void mine(BlockHitResult bh, boolean click) {
		ServerPlayer p = this.player;
		BlockPos pos = bh.getBlockPos();
		BlockState state = p.level().getBlockState(pos);
		if (state.isAir()) {
			this.abortBreaking();
			return;
		}
		if (p.getAbilities().instabuild) {
			if (this.breakDelay > 0) {
				this.breakDelay--;
				return;
			}
			this.sendPacket(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, bh.getDirection(), this.nextSeq()));
			this.sendPacket(ServerboundPunchPacket.INSTANCE);
			this.breakDelay = 5;
			return;
		}
		if (this.breakPos == null || !this.breakPos.equals(pos)) {
			this.abortBreaking();
			this.sendPacket(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, bh.getDirection(), this.nextSeq()));
			this.breakPos = pos;
			this.breakProgress = 0.0F;
			if (state.getDestroyProgress(p, p.level(), pos) >= 1.0F) {
				this.breakPos = null;
				this.breakDelay = 5;
			}
			this.sendPacket(ServerboundPunchPacket.INSTANCE);
			return;
		}
		if (this.breakDelay > 0) {
			this.breakDelay--;
			return;
		}
		this.breakProgress += state.getDestroyProgress(p, p.level(), pos);
		this.sendPacket(ServerboundPunchPacket.INSTANCE);
		if (this.breakProgress >= 1.0F) {
			this.sendPacket(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, bh.getDirection(), this.nextSeq()));
			this.breakPos = null;
			this.breakProgress = 0.0F;
			this.breakDelay = 5;
		}
	}

	private void abortBreaking() {
		if (this.breakPos != null && this.player != null) {
			this.sendPacket(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.breakPos, Direction.DOWN, this.nextSeq()));
			this.breakPos = null;
			this.breakProgress = 0.0F;
		}
	}

	private void rightTick() {
		ServerPlayer p = this.player;
		if (p.isUsingItem()) {
			this.rightWasHolding = true;
			return;
		}
		if (this.rightDelay > 0 && !this.rightM.click) {
			return;
		}
		this.rightDelay = 4;
		HitResult h = this.hit();
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = p.getItemInHand(hand);
			if (h instanceof EntityHitResult eh) {
				Vec3 loc = eh.getLocation().subtract(eh.getEntity().getX(), eh.getEntity().getY(), eh.getEntity().getZ());
				this.sendPacket(new net.minecraft.network.protocol.game.ServerboundInteractPacket(eh.getEntity().getId(), hand, loc, p.isShiftKeyDown()));
				// the packet handler already ran interactOn; a second try for the same hand is not needed
				InteractionResult r = p.level().getEntity(eh.getEntity().getId()) == null ? InteractionResult.PASS : InteractionResult.PASS;
				if (r.consumesAction()) {
					return;
				}
			} else if (h instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
				ServerLevel level = p.level();
				if (p.isWithinBlockInteractionRange(bh.getBlockPos(), 1.0) && level.mayInteract(p, bh.getBlockPos())) {
					InteractionResult r = p.gameMode.useItemOn(p, level, stack, hand, bh);
					this.log("useItemOn " + hand + " -> " + r);
					if (r instanceof InteractionResult.Success s && s.shouldSwing()) {
						p.swing(hand, stack.getInteractAnimation(), false);
					}
					if (r.consumesAction()) {
						return;
					}
				}
			}
			if (!stack.isEmpty()) {
				ItemStack before = stack.copy();
				this.sendPacket(new ServerboundUseItemPacket(hand, this.nextSeq(), p.getYRot(), p.getXRot()));
				if (p.isUsingItem()) {
					this.rightWasHolding = true;
					return;
				}
				// like the real client: if this hand did nothing, the other hand gets its turn (shield in the off hand)
				if (!ItemStack.matches(before, p.getItemInHand(hand))) {
					return;
				}
			}
		}
	}

	@Override
	public void leftMouse(int ticks) {
		if (ticks == 0) {
			this.leftM.click = true;
		} else {
			this.leftM.remaining = ticks > 0 && this.leftM.remaining != 0 ? Math.max(this.leftM.remaining, ticks + 1) : ticks;
		}
	}

	@Override
	public void rightMouse(int ticks) {
		if (ticks == 0) {
			this.rightM.click = true;
			this.rightDelay = 0;
		} else {
			// calling rightMouse(n) again every tick keeps the button held without a release gap
			this.rightM.remaining = ticks > 0 && this.rightM.remaining != 0 ? Math.max(this.rightM.remaining, ticks + 1) : ticks;
		}
	}

	@Override
	public void middleMouse() {
		HitResult h = this.hit();
		if (h instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
			this.sendPacket(new ServerboundPickItemFromBlockPacket(bh.getBlockPos(), false));
		} else if (h instanceof EntityHitResult eh) {
			this.sendPacket(new net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket(eh.getEntity().getId(), false));
		}
	}

	@Override
	public void stopInteracting() {
		this.leftM.remaining = 0;
		this.leftM.click = false;
		this.rightM.remaining = 0;
		this.rightM.click = false;
		this.abortBreaking();
		if (this.player != null && this.player.isUsingItem()) {
			this.sendPacket(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
		}
		this.rightWasHolding = false;
	}

	@Override
	public void stopAll() {
		this.stopMoving();
		this.stopInteracting();
	}

	// ---------------------------------------------------------------- movement setters

	@Override
	public void setForward(boolean on) {
		this.fwd = on;
	}

	@Override
	public void setBackward(boolean on) {
		this.back = on;
	}

	@Override
	public void setLeft(boolean on) {
		this.left = on;
	}

	@Override
	public void setRight(boolean on) {
		this.right = on;
	}

	@Override
	public void setJump(boolean on) {
		this.jump = on;
	}

	@Override
	public void setSneak(boolean on) {
		this.sneak = on;
	}

	@Override
	public void setSprint(boolean on) {
		this.sprintKey = on;
	}

	@Override
	public void stopMoving() {
		this.fwd = this.back = this.left = this.right = this.jump = this.sprintKey = false;
		this.walkTicks = this.sprintTicks = this.jumpTicks = 0;
		this.stopPath();
	}

	@Override
	public void walk(int ticks) {
		this.stopPath();
		this.sprintTicks = 0;
		this.walkTicks = ticks == 0 ? -1 : ticks;
	}

	@Override
	public void sprint(int ticks) {
		this.stopPath();
		this.walkTicks = 0;
		this.sprintTicks = ticks == 0 ? -1 : ticks;
	}

	@Override
	public void jump(int ticks) {
		this.jumpTicks = ticks == 0 ? 1 : ticks;
	}

	// ---------------------------------------------------------------- inventory

	@Override
	public void hotbarSlot(int slot) {
		this.sendPacket(new ServerboundSetCarriedItemPacket(Mth.clamp(slot, 0, 8)));
	}

	@Override
	public void swapHands() {
		this.sendPacket(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
	}

	/** Menu slot id of an inventory index in the player's own inventory menu (container id 0). */
	static int menuSlot(int inv) {
		if (inv >= 0 && inv <= 8) {
			return 36 + inv;
		}
		if (inv >= 9 && inv <= 35) {
			return inv;
		}
		if (inv >= 36 && inv <= 39) {
			return 44 - inv; // boots 36 -> 8, legs -> 7, chest -> 6, helmet -> 5
		}
		return 45;
	}

	private void click(int menuSlot, int button, ContainerInput type) {
		var menu = this.player.containerMenu;
		this.sendPacket(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) menuSlot, (byte) button, type,
			new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
	}

	@Override
	public void swapHandWithSlot(int slot) {
		int sel = this.player.getInventory().getSelectedSlot();
		if (slot == sel) {
			return;
		}
		this.closeContainerIfAny();
		this.click(menuSlot(slot), sel, ContainerInput.SWAP);
	}

	@Override
	public void swapSlots(int a, int b) {
		if (a == b) {
			return;
		}
		this.closeContainerIfAny();
		if (a >= 0 && a <= 8) {
			this.click(menuSlot(b), a, ContainerInput.SWAP);
		} else if (b >= 0 && b <= 8) {
			this.click(menuSlot(a), b, ContainerInput.SWAP);
		} else {
			this.click(menuSlot(a), 0, ContainerInput.PICKUP);
			this.click(menuSlot(b), 0, ContainerInput.PICKUP);
			this.click(menuSlot(a), 0, ContainerInput.PICKUP);
		}
	}

	private void closeContainerIfAny() {
		if (this.player.containerMenu != this.player.inventoryMenu) {
			this.sendPacket(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(this.player.containerMenu.containerId));
		}
	}

	@Override
	public String throwItems(String amount) {
		ServerPlayer p = this.player;
		ItemStack held = p.getMainHandItem();
		if (held.isEmpty()) {
			return "Nothing in the main hand.";
		}
		switch (amount.toLowerCase(java.util.Locale.ROOT)) {
			case "stack" -> {
				this.sendPacket(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS, BlockPos.ZERO, Direction.DOWN));
				return "Threw the whole held stack.";
			}
			case "all" -> {
				int total = 0;
				var inv = p.getInventory();
				for (int i = 0; i < 36; i++) {
					ItemStack s = inv.getItem(i);
					if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, held)) {
						total += s.getCount();
						p.drop(s.copy(), true, Prediction.PREDICTED);
						inv.setItem(i, ItemStack.EMPTY);
					}
				}
				ItemStack off = p.getOffhandItem();
				if (!off.isEmpty() && ItemStack.isSameItemSameComponents(off, held)) {
					total += off.getCount();
					p.drop(off.copy(), true, Prediction.PREDICTED);
					p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
				}
				return "Threw " + total + " items.";
			}
			default -> {
				int n;
				try {
					n = Integer.parseInt(amount);
				} catch (NumberFormatException e) {
					return "Use a number 1-99, stack or all.";
				}
				if (n < 1 || n > 99) {
					return "Number must be 1-99.";
				}
				int real = Math.min(n, held.getCount());
				p.drop(held.split(real), true, Prediction.PREDICTED);
				return real == n ? "Threw " + real + "." : "Held stack only had " + real + ", threw those.";
			}
		}
	}

	// ---------------------------------------------------------------- chat

	@Override
	public void chat(String message) {
		ServerPlayer p = this.player;
		if (message.startsWith("/")) {
			this.sendPacket(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(message.substring(1)));
		} else {
			this.server.getPlayerList().broadcastChatMessage(net.minecraft.network.chat.PlayerChatMessage.system(message), p,
				net.minecraft.network.chat.ChatType.bind(net.minecraft.network.chat.ChatType.CHAT, p));
		}
	}

	@Override
	public void addChatListener(Consumer<String> listener) {
		this.chatListeners.add(listener);
	}

	@Override
	public List<String> chatLog() {
		return List.copyOf(this.chatLog);
	}

	// ---------------------------------------------------------------- path

	@Override
	public PathPlan planPath(BlockPos target) {
		ServerLevel level = this.player.level();
		BlockPos start = Pathfinder.snapGoal(level, this.player.blockPosition());
		BlockPos goal = Pathfinder.snapGoal(level, target);
		var r = Pathfinder.find(level, start, goal, 30000);
		List<BlockPos> wp = r.nodes().stream().map(Pathfinder.Node::pos).toList();
		String msg = r.reachedGoal() ? "Path found: " + wp.size() + " steps, cost " + Math.round(r.cost())
			: r.isEmpty() ? "No path (goal unreachable or blocked)" : "Goal unreachable, partial path of " + wp.size() + " steps toward the closest point";
		return new PathPlan(r.reachedGoal(), !r.reachedGoal() && !r.isEmpty(), r.visited(), r.cost(), wp, msg);
	}

	@Override
	public PathPlan goTo(BlockPos target, boolean sprint) {
		this.stopMoving();
		this.pathGoal = Pathfinder.snapGoal(this.player.level(), target);
		this.pathSprint = sprint;
		this.pathReplans = 0;
		this.pathChain = 0;
		PathPlan plan = this.planPath(target);
		if (!plan.waypoints().isEmpty() && plan.waypoints().size() > 1) {
			this.setPath(Pathfinder.find(this.player.level(), Pathfinder.snapGoal(this.player.level(), this.player.blockPosition()), this.pathGoal, 30000).nodes());
		}
		this.log(plan.message());
		return plan;
	}

	private void setPath(List<Pathfinder.Node> nodes) {
		this.path = nodes;
		this.pathIndex = 1;
		this.stuckTicks = 0;
		this.stuckRef = this.player.position();
	}

	@Override
	public void stopPath() {
		this.path = List.of();
		this.fwd = false;
		this.sprintKey = this.sprintKey && this.pathGoal == null;
		if (this.pathGoal != null) {
			this.sprintKey = false;
			this.jump = false;
		}
		this.pathGoal = null;
	}

	@Override
	public boolean isPathing() {
		return !this.path.isEmpty();
	}

	@Override
	public List<BlockPos> currentPath() {
		if (this.path.isEmpty()) {
			return List.of();
		}
		List<BlockPos> out = new ArrayList<>();
		for (int i = Math.max(0, this.pathIndex); i < this.path.size(); i++) {
			out.add(this.path.get(i).pos());
		}
		return out;
	}

	private void tickPath() {
		if (this.path.isEmpty()) {
			return;
		}
		ServerPlayer p = this.player;
		if (this.pathIndex >= this.path.size()) {
			this.finishPath();
			return;
		}
		Pathfinder.Node node = this.path.get(this.pathIndex);
		BlockPos np = node.pos();
		Vec3 pos = p.position();
		double cx = np.getX() + 0.5;
		double cz = np.getZ() + 0.5;
		double dx = cx - pos.x;
		double dz = cz - pos.z;
		double dh = Math.sqrt(dx * dx + dz * dz);
		double dy = pos.y - np.getY();

		boolean vertical = node.move() == Pathfinder.Move.SWIM_UP || node.move() == Pathfinder.Move.SWIM_DOWN
			|| node.move() == Pathfinder.Move.CLIMB_UP || node.move() == Pathfinder.Move.CLIMB_DOWN;
		boolean reached = vertical ? dh < 0.6 && Math.abs(dy) < 0.7 : dh < 0.35 && dy > -0.5 && dy < 1.1;
		if (!vertical && dh < 0.55 && Math.abs(dy) < 0.6 && this.pathIndex + 1 < this.path.size() && node.move() != Pathfinder.Move.DESCEND) {
			reached = true; // cut corners on flat ground
		}
		if (reached) {
			this.pathIndex++;
			this.stuckTicks = 0;
			this.stuckRef = pos;
			if (this.pathIndex >= this.path.size()) {
				this.finishPath();
			}
			return;
		}

		// open doors / gates in the way
		if (node.door() && dh < 2.2) {
			this.openDoorsAt(np);
		}

		if (dh > 0.05) {
			float yaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
			this.look(yaw, 0.0F);
		}
		this.fwd = true;
		this.sprintKey = this.pathSprint && node.move() != Pathfinder.Move.ASCEND;
		boolean needJump = node.move() == Pathfinder.Move.ASCEND && p.onGround()
			|| (node.move() == Pathfinder.Move.SWIM_UP || node.move() == Pathfinder.Move.CLIMB_UP) || (p.isInWater() && np.getY() >= p.getBlockY());
		this.jump = needJump;
		if (node.move() == Pathfinder.Move.SWIM_DOWN || node.move() == Pathfinder.Move.CLIMB_DOWN) {
			this.sneak = node.move() == Pathfinder.Move.CLIMB_DOWN;
		} else {
			this.sneak = false;
		}

		// stuck / off-course detection
		if (++this.stuckTicks >= 40) {
			double moved = pos.distanceTo(this.stuckRef);
			this.stuckTicks = 0;
			this.stuckRef = pos;
			if (moved < 0.4) {
				this.replan("stuck");
			}
		} else if (dh > 4.0 && !vertical) {
			this.replan("off course");
		}
	}

	private void openDoorsAt(BlockPos feet) {
		ServerPlayer p = this.player;
		if (this.rightDelay > 0) {
			return;
		}
		for (BlockPos q : new BlockPos[] {feet, feet.above()}) {
			BlockState s = p.level().getBlockState(q);
			boolean closed = (s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && !s.getValue(net.minecraft.world.level.block.DoorBlock.OPEN))
				|| (s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock && !s.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN));
			if (closed) {
				this.lookAt(Vec3.atCenterOf(q));
				HitResult h = this.hit();
				if (h instanceof BlockHitResult bh && bh.getBlockPos().equals(q)) {
					var r = p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND, bh);
					this.log("open door " + q + " -> " + r);
					p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
					this.rightDelay = 6;
					return;
				}
			}
		}
	}

	private void replan(String why) {
		if (this.pathGoal == null) {
			return;
		}
		if (++this.pathReplans > 4) {
			this.log("path failed: " + why);
			this.stopMoving();
			return;
		}
		this.log("replan (" + why + ")");
		var r = Pathfinder.find(this.player.level(), Pathfinder.snapGoal(this.player.level(), this.player.blockPosition()), this.pathGoal, 30000);
		if (r.isEmpty()) {
			this.log("path failed: no route");
			this.stopMoving();
			return;
		}
		this.setPath(r.nodes());
	}

	private void finishPath() {
		BlockPos goal = this.pathGoal;
		this.path = List.of();
		this.fwd = false;
		this.jump = false;
		this.sneak = false;
		this.sprintKey = false;
		if (goal != null && this.player.blockPosition().distSqr(goal) > 2.0 && this.pathChain++ < 25) {
			// partial path: keep going from here as more chunks load / new area is reachable
			var r = Pathfinder.find(this.player.level(), Pathfinder.snapGoal(this.player.level(), this.player.blockPosition()), goal, 30000);
			if (!r.isEmpty()) {
				this.log("continuing toward goal, " + r.nodes().size() + " steps");
				this.setPath(r.nodes());
				return;
			}
		}
		this.log("arrived / path ended at " + this.player.blockPosition().toShortString());
		this.pathGoal = null;
	}

	// ---------------------------------------------------------------- state readers

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public java.util.UUID uuid() {
		return this.id;
	}

	@Override
	public ServerPlayer player() {
		return this.player;
	}

	@Override
	public boolean isOnline() {
		return this.player != null;
	}

	@Override
	public Vec3 position() {
		return this.player.position();
	}

	@Override
	public Vec3 cameraPos() {
		return this.player.getEyePosition();
	}

	@Override
	public double distanceTo(Entity target, boolean nearestPoint) {
		Vec3 eye = this.cameraPos();
		if (!nearestPoint) {
			return eye.distanceTo(target.getBoundingBox().getCenter());
		}
		return Math.sqrt(target.getBoundingBox().distanceToSqr(eye));
	}

	@Override
	public double distanceTo(BlockPos target, boolean nearestPoint) {
		Vec3 eye = this.cameraPos();
		if (!nearestPoint) {
			return eye.distanceTo(Vec3.atCenterOf(target));
		}
		return Math.sqrt(new AABB(target).distanceToSqr(eye));
	}

	@Override
	public Vec3 velocity() {
		return this.player.getDeltaMovement();
	}

	@Override
	public float yaw() {
		return this.player.getYRot();
	}

	@Override
	public float pitch() {
		return this.player.getXRot();
	}

	@Override
	public Vec3 lookVector() {
		return this.player.getViewVector(1.0F);
	}

	@Override
	public boolean onGround() {
		return this.player.onGround();
	}

	@Override
	public boolean isSprinting() {
		return this.player.isSprinting();
	}

	@Override
	public boolean isSneaking() {
		return this.player.isShiftKeyDown();
	}

	@Override
	public boolean isUsingItem() {
		return this.player.isUsingItem();
	}

	@Override
	public float health() {
		return this.player.getHealth();
	}

	@Override
	public int food() {
		return this.player.getFoodData().getFoodLevel();
	}

	@Override
	public Entity aimingEntity() {
		return this.aimingAt() instanceof EntityHitResult e ? e.getEntity() : null;
	}

	@Override
	public BlockPos aimingBlock() {
		return this.aimingAt() instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
	}

	@Override
	public float attackStrength() {
		return this.player.getAttackStrengthScale(0.0F);
	}

	@Override
	public int selectedHotbarSlot() {
		return this.player.getInventory().getSelectedSlot();
	}

	@Override
	public ItemStack item(int slot) {
		var inv = this.player.getInventory();
		if (slot >= 0 && slot <= 35) {
			return inv.getItem(slot);
		}
		return switch (slot) {
			case 36 -> this.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET);
			case 37 -> this.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS);
			case 38 -> this.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
			case 39 -> this.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
			case 40 -> this.player.getOffhandItem();
			default -> ItemStack.EMPTY;
		};
	}

	@Override
	public ItemStack mainHand() {
		return this.player.getMainHandItem();
	}

	@Override
	public ItemStack offHand() {
		return this.player.getOffhandItem();
	}

	@Override
	public List<ItemStack> inventory() {
		List<ItemStack> out = new ArrayList<>(41);
		for (int i = 0; i <= 40; i++) {
			out.add(this.item(i));
		}
		return out;
	}

	@Override
	public List<Entity> entitiesNear(double range) {
		List<Entity> list = new ArrayList<>(this.player.level().getEntities(this.player, this.player.getBoundingBox().inflate(range), e -> true));
		list.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(this.player)));
		return list;
	}

	@Override
	public void teleport(Vec3 pos) {
		this.player.teleportTo(this.player.level(), pos.x, pos.y, pos.z, java.util.Set.of(), this.player.getYRot(), this.player.getXRot(), false);
	}

	@Override
	public void setBrain(BotBrain brain) {
		this.brain = brain;
	}

	@Override
	public void disconnect() {
		BotManager.disconnect(this.name);
	}
}
