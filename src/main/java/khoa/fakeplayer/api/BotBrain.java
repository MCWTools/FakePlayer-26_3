package khoa.fakeplayer.api;

/**
 * Custom AI for a bot. {@link #tick} runs on the server thread once per tick, before the built-in behaviours
 * (walk/goto/mouse holds) are applied, so a brain may drive the bot purely through the {@link BotHandle} setters.
 */
@FunctionalInterface
public interface BotBrain {
	void tick(BotHandle bot);
}
