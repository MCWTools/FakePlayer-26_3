package khoa.fakeplayer.bot;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import khoa.fakeplayer.FakePlayerMod;

/**
 * Per-bot look that must survive a restart: skin textures property and player model. Stored in
 * {@code config/fakeplayer/bots.json}. Inventory, position and health live in the normal player data file.
 */
public final class BotSettings {
	public static final class Entry {
		/** Base64 "textures" property value, or null for the default skin. */
		public String skinValue;
		public String skinSignature;
		/** "steve", "alex" or null (= whatever the skin says). */
		public String model;
		/** Name of the Mojang account the skin was copied from, for display only. */
		public String skinSource;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<TreeMap<String, Entry>>() { }.getType();
	private static Map<String, Entry> data;

	private BotSettings() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("fakeplayer").resolve("bots.json");
	}

	private static synchronized Map<String, Entry> load() {
		if (data == null) {
			data = new TreeMap<>();
			Path f = file();
			if (Files.isRegularFile(f)) {
				try (Reader r = Files.newBufferedReader(f)) {
					Map<String, Entry> read = GSON.fromJson(r, TYPE);
					if (read != null) {
						data = read;
					}
				} catch (IOException | RuntimeException e) {
					FakePlayerMod.LOGGER.error("Could not read {}", f, e);
				}
			}
		}
		return data;
	}

	public static synchronized Entry get(String name) {
		return load().get(name.toLowerCase(Locale.ROOT));
	}

	public static synchronized Entry edit(String name) {
		return load().computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new Entry());
	}

	public static synchronized void save() {
		Path f = file();
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f)) {
				GSON.toJson(load(), TYPE, w);
			}
		} catch (IOException e) {
			FakePlayerMod.LOGGER.error("Could not save {}", f, e);
		}
	}
}
