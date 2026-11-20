package khoa.fakeplayer.inv;

import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Remote view of a bot's inventory, usable from anywhere (same menu works across dimensions and distance).
 * <pre>
 * row 0 : helmet | chestplate | leggings | boots | MAIN HAND | OFF HAND | (decor)
 * row 1-3: inventory (27 slots, slot 9-35)
 * row 4 : hotbar (slot 0-8)
 * </pre>
 * The main-hand cell shows the currently selected hotbar item; the matching hotbar cell is greyed out while it is selected.
 */
public final class InvseeMenu extends AbstractContainerMenu {
	private enum Kind { ARMOR, MAIN, OFF, FILLER, INV, HOTBAR }

	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final String[] ARMOR_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots"};

	private final MinecraftServer server;
	private final UUID botId;
	private final Component botName;

	public static void open(ServerPlayer viewer, ServerPlayer bot) {
		MinecraftServer server = viewer.level().getServer();
		UUID id = bot.getUUID();
		Component title = Component.literal(bot.getGameProfile().name() + " - Armor | Main | Off | Inventory | Hotbar");
		viewer.openMenu(new MenuProvider() {
			@Override
			public Component getDisplayName() {
				return title;
			}

			@Override
			public AbstractContainerMenu createMenu(int containerId, Inventory inv, Player p) {
				return new InvseeMenu(containerId, inv, server, id, title);
			}
		});
	}

	private InvseeMenu(int containerId, Inventory viewerInv, MinecraftServer server, UUID botId, Component title) {
		super(MenuType.GENERIC_9x5, containerId);
		this.server = server;
		this.botId = botId;
		this.botName = title;
		Container dummy = new SimpleContainer(1);
		for (int i = 0; i < 4; i++) {
			this.addSlot(new ViewSlot(dummy, Kind.ARMOR, i));
		}
		this.addSlot(new ViewSlot(dummy, Kind.MAIN, 0));
		this.addSlot(new ViewSlot(dummy, Kind.OFF, 0));
		for (int i = 0; i < 3; i++) {
			this.addSlot(new ViewSlot(dummy, Kind.FILLER, i));
		}
		for (int i = 9; i < 36; i++) {
			this.addSlot(new ViewSlot(dummy, Kind.INV, i));
		}
		for (int i = 0; i < 9; i++) {
			this.addSlot(new ViewSlot(dummy, Kind.HOTBAR, i));
		}
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				this.addSlot(new Slot(viewerInv, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			this.addSlot(new Slot(viewerInv, col, 8 + col * 18, 198));
		}
	}

	private ServerPlayer bot() {
		return this.server.getPlayerList().getPlayer(this.botId);
	}

	private static ItemStack pane(net.minecraft.world.level.ItemLike item, String label) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_NAME, Component.literal(label).withStyle(net.minecraft.ChatFormatting.RESET));
		return s;
	}

	/** One cell of the bot view. Reads and writes the bot's real inventory live. */
	private final class ViewSlot extends Slot {
		private final Kind kind;
		private final int index;

		ViewSlot(Container dummy, Kind kind, int index) {
			super(dummy, 0, 0, 0);
			this.kind = kind;
			this.index = index;
		}

		/** Placeholder shown while the real cell is empty or locked; null when the real item is shown. */
		ItemStack placeholder() {
			ServerPlayer b = InvseeMenu.this.bot();
			return switch (this.kind) {
				case FILLER -> pane(Items.STAINED_GLASS_PANE.black(), " ");
				case ARMOR -> this.real().isEmpty() ? pane(Items.STAINED_GLASS_PANE.lightBlue(), ARMOR_NAMES[this.index] + " (empty)") : null;
				case MAIN -> this.real().isEmpty() ? pane(Items.STAINED_GLASS_PANE.lime(), "Main hand (empty)") : null;
				case OFF -> this.real().isEmpty() ? pane(Items.STAINED_GLASS_PANE.yellow(), "Off hand (empty)") : null;
				case HOTBAR -> b != null && b.getInventory().getSelectedSlot() == this.index
					? pane(Items.STAINED_GLASS_PANE.lime(), "Selected: shown in Main hand") : null;
				case INV -> null;
			};
		}

		boolean isPlaceholderActive() {
			return this.placeholder() != null;
		}

		ItemStack real() {
			ServerPlayer b = InvseeMenu.this.bot();
			if (b == null) {
				return ItemStack.EMPTY;
			}
			Inventory inv = b.getInventory();
			return switch (this.kind) {
				case ARMOR -> b.getItemBySlot(ARMOR[this.index]);
				case MAIN -> inv.getItem(inv.getSelectedSlot());
				case OFF -> b.getOffhandItem();
				case INV, HOTBAR -> inv.getItem(this.index);
				case FILLER -> ItemStack.EMPTY;
			};
		}

		private boolean locked() {
			if (this.kind == Kind.FILLER) {
				return true;
			}
			if (this.kind == Kind.HOTBAR) {
				ServerPlayer b = InvseeMenu.this.bot();
				return b == null || b.getInventory().getSelectedSlot() == this.index;
			}
			return false;
		}

		@Override
		public ItemStack getItem() {
			ItemStack ph = this.placeholder();
			return ph != null ? ph : this.real();
		}

		@Override
		public boolean hasItem() {
			return !this.getItem().isEmpty();
		}

		@Override
		public void set(ItemStack stack) {
			this.write(stack);
		}

		@Override
		public void setByPlayer(ItemStack stack) {
			this.write(stack);
		}

		@Override
		public void setByPlayer(ItemStack stack, ItemStack previous) {
			this.write(stack);
		}

		private void write(ItemStack stack) {
			ServerPlayer b = InvseeMenu.this.bot();
			if (b == null || this.locked()) {
				return;
			}
			Inventory inv = b.getInventory();
			switch (this.kind) {
				case ARMOR -> b.setItemSlot(ARMOR[this.index], stack);
				case MAIN -> inv.setItem(inv.getSelectedSlot(), stack);
				case OFF -> b.setItemSlot(EquipmentSlot.OFFHAND, stack);
				case INV, HOTBAR -> inv.setItem(this.index, stack);
				case FILLER -> { }
			}
			b.inventoryMenu.broadcastChanges();
		}

		@Override
		public ItemStack remove(int amount) {
			if (this.locked() || this.real().isEmpty()) {
				return ItemStack.EMPTY;
			}
			ItemStack r = this.real();
			ItemStack out = r.split(amount);
			if (r.isEmpty()) {
				this.write(ItemStack.EMPTY);
			} else {
				this.write(r);
			}
			return out;
		}

		@Override
		public boolean mayPickup(Player player) {
			return !this.locked() && !this.real().isEmpty();
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			if (this.locked()) {
				return false;
			}
			ServerPlayer b = InvseeMenu.this.bot();
			if (b == null) {
				return false;
			}
			return switch (this.kind) {
				case ARMOR -> b.isEquippableInSlot(stack, ARMOR[this.index]);
				default -> true;
			};
		}

		@Override
		public int getMaxStackSize() {
			return this.kind == Kind.ARMOR ? 1 : 99;
		}

		@Override
		public int getMaxStackSize(ItemStack stack) {
			return this.kind == Kind.ARMOR ? 1 : stack.getMaxStackSize();
		}

		@Override
		public void setChanged() {
		}
	}

	@Override
	public void clicked(int slotIndex, int button, ContainerInput type, Player player) {
		if (slotIndex >= 0 && slotIndex < 45 && this.slots.get(slotIndex) instanceof ViewSlot vs) {
			// cells that currently show a placeholder: only "put the cursor item here" is meaningful
			if (vs.isPlaceholderActive()) {
				if ((type == ContainerInput.PICKUP || type == ContainerInput.PICKUP_ALL) && !vs.locked() && vs.kind != Kind.FILLER && !this.getCarried().isEmpty() && vs.mayPlace(this.getCarried())) {
					int amount = button == 1 ? 1 : (vs.kind == Kind.ARMOR ? 1 : this.getCarried().getCount());
					vs.set(this.getCarried().split(amount));
				}
				this.broadcastChanges();
				return;
			}
			if (type == ContainerInput.PICKUP_ALL || type == ContainerInput.QUICK_CRAFT || type == ContainerInput.CLONE && false) {
				this.broadcastFullState();
				return;
			}
		}
		if (type == ContainerInput.PICKUP_ALL || type == ContainerInput.QUICK_CRAFT) {
			this.broadcastFullState();
			return;
		}
		super.clicked(slotIndex, button, type, player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!(slot instanceof ViewSlot vs && vs.isPlaceholderActive()) && slot.hasItem() && slot.mayPickup(player)) {
			ItemStack stack = slot.getItem();
			ItemStack copy = stack.copy();
			boolean moved;
			if (index < 45) {
				moved = this.moveItemStackTo(stack, 45, 81, true);
			} else {
				moved = this.moveItemStackTo(stack, 9, 45, false);
			}
			if (!moved) {
				return ItemStack.EMPTY;
			}
			if (stack.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
			return copy;
		}
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return this.bot() != null && player.isAlive();
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
		return !(target instanceof ViewSlot);
	}
}
