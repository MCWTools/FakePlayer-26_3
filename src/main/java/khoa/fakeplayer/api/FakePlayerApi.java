package khoa.fakeplayer.api;

import java.util.Collection;
import java.util.Optional;

import khoa.fakeplayer.bot.BotManager;

/** Entry point for other mods (for example a scripting mod) that want to control bots. Server thread only. */
public final class FakePlayerApi {
	private FakePlayerApi() {
	}

	public static Optional<BotHandle> get(String name) {
		return Optional.ofNullable(BotManager.get(name));
	}

	public static Collection<? extends BotHandle> all() {
		return BotManager.all();
	}
}
