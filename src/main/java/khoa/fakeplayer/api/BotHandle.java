package khoa.fakeplayer.api;

import java.util.UUID;
import java.util.function.Consumer;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Control surface of one bot. All methods must be called on the server thread. Everything a bot does goes through
 * the same server packet handlers a real client would use (input, use item, attack, container click, ...).
 * Durations are in ticks: {@code -1} = until stopped, {@code 0} = a single action.
 */
public interface BotHandle {
	String name();

	UUID uuid();

	/** The live server player, or null while the bot is respawning or offline. */
	ServerPlayer player();

	boolean isOnline();

	// ---- raw input (hold until changed) ----
	void setForward(boolean on);

	void setBackward(boolean on);

	void setLeft(boolean on);

	void setRight(boolean on);

	void setJump(boolean on);

	void setSneak(boolean on);

	void setSprint(boolean on);

	/** Releases every movement key, cancels timers and any goto. */
	void stopMoving();

	// ---- timed movement ----
	/** Walk forward for {@code ticks} ({@code -1} = until stopped). */
	void walk(int ticks);

	void sprint(int ticks);

	/** One jump ({@code ticks == 0}), or keep jumping for {@code ticks}. */
	void jump(int ticks);

	// ---- looking ----
	void look(float yaw, float pitch);

	void lookAt(Vec3 target);

	// ---- mouse ----
	void leftMouse(int ticks);

	/** One click. */
	default void leftMouse() {
		this.leftMouse(0);
	}

	void rightMouse(int ticks);

	default void rightMouse() {
		this.rightMouse(0);
	}

	void middleMouse();

	/** Releases left/right mouse holds and cancels block breaking. */
	void stopInteracting();

	/** Stops moving, the mouse and any goto. */
	void stopAll();

	// ---- inventory ----
	/** Selects hotbar slot 0-8. */
	void hotbarSlot(int slot);

	/** Swaps main hand and off hand. */
	void swapHands();

	/** Swaps the held item with inventory slot {@code slot} (0-8 hotbar, 9-35 inventory). */
	void swapHandWithSlot(int slot);

	/** Swaps two inventory slots (0-8 hotbar, 9-35 inventory, 36-39 boots..helmet, 40 off hand). */
	void swapSlots(int a, int b);

	/** Drops items. {@code amount}: 1-99 items of the held stack, {@code "stack"}, or {@code "all"} (same item in every slot). */
	String throwItems(String amount);

	// ---- chat ----
	/** Says {@code message} in chat, or runs it as a command when it starts with '/'. */
	void chat(String message);

	void addChatListener(Consumer<String> listener);

	// ---- pathfinding ----
	/** Starts walking to the position with A*. The result describes the plan; arrival is reported by {@link #isPathing()}. */
	PathPlan goTo(BlockPos target, boolean sprint);

	void stopPath();

	boolean isPathing();

	// ---- AI ----
	void setBrain(BotBrain brain);

	void disconnect();

	// ---- reading state (exact server-side data) ----
	Vec3 position();

	/** Eye position (the camera). */
	Vec3 cameraPos();

	/**
	 * Distance from the camera to {@code target}. With {@code nearestPoint} the distance is measured to the closest
	 * point of the entity's hitbox instead of its centre (this is what attack reach uses).
	 */
	double distanceTo(Entity target, boolean nearestPoint);

	double distanceTo(BlockPos target, boolean nearestPoint);

	Vec3 velocity();

	float yaw();

	float pitch();

	Vec3 lookVector();

	boolean onGround();

	boolean isSprinting();

	boolean isSneaking();

	boolean isUsingItem();

	float health();

	int food();

	/** What the crosshair is on (entity first if nearer, else block, else a MISS result), using real reach. */
	HitResult aimingAt();

	/** The entity under the crosshair within reach, or null. */
	Entity aimingEntity();

	/** The block under the crosshair within reach, or null. */
	BlockPos aimingBlock();

	/** Attack cooldown progress 0..1. */
	float attackStrength();

	int selectedHotbarSlot();

	/** Inventory slot 0-8 hotbar, 9-35 inventory, 36-39 boots..helmet, 40 off hand. Never null. */
	ItemStack item(int slot);

	ItemStack mainHand();

	ItemStack offHand();

	List<ItemStack> inventory();

	/** Entities within {@code range} blocks of the bot (excluding itself), nearest first. */
	List<Entity> entitiesNear(double range);

	/** Pathfinder plan without moving (for planning in scripts). */
	PathPlan planPath(BlockPos target);

	/** Remaining waypoints of the active path (empty when idle). */
	List<BlockPos> currentPath();

	/** Last chat / system message lines the bot received (newest last). */
	List<String> chatLog();

	void teleport(Vec3 pos);
}
