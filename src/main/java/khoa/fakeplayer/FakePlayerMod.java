package khoa.fakeplayer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import khoa.fakeplayer.bot.BotManager;
import khoa.fakeplayer.command.FakePlayerCommand;
import khoa.fakeplayer.debug.PathDebug;

public class FakePlayerMod implements ModInitializer {
	/** /fakeplayer logs true|false: show bot action messages in chat. */
	public static volatile boolean logs = true;

	public static final String MOD_ID = "fakeplayer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> FakePlayerCommand.register(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			BotManager.tick();
			PathDebug.tick();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> BotManager.disconnectAll());
		LOGGER.info("FakePlayer loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
