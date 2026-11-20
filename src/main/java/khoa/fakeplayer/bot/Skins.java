package khoa.fakeplayer.bot;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import net.minecraft.server.MinecraftServer;

/** Skin helpers: fetch a Mojang skin by account name and build/patch the "textures" profile property. */
public final class Skins {
	/** Texture hashes of the two built-in default skins on textures.minecraft.net. */
	public static final String STEVE_URL = "http://textures.minecraft.net/texture/31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";
	public static final String ALEX_URL = "http://textures.minecraft.net/texture/46acd06e8483b176e8ea39fc12fe105eb3a2a4970f5100057e9d84d4b60bdfa7";

	private Skins() {
	}

	/** Result of a lookup; value/signature may be null when the account has no textures. */
	public record Fetched(String sourceName, String value, String signature) {
	}

	/** Blocking Mojang lookups run off the server thread. Completes with empty when the account or textures are missing. */
	public static CompletableFuture<Optional<Fetched>> fetch(MinecraftServer server, String accountName) {
		return CompletableFuture.supplyAsync(() -> {
			Optional<GameProfile> profile = server.services().profileResolver().fetchByName(accountName);
			if (profile.isEmpty()) {
				return Optional.<Fetched>empty();
			}
			for (Property p : profile.get().properties().get("textures")) {
				return Optional.of(new Fetched(profile.get().name(), p.value(), p.signature()));
			}
			return Optional.<Fetched>empty();
		});
	}

	/** Rewrites the textures JSON: forces the model (steve = wide, alex = slim) and, if there is no skin, uses the default skin. */
	public static Fetched withModel(Fetched in, UUID id, String botName, String model) {
		JsonObject root;
		if (in != null && in.value() != null) {
			root = JsonParser.parseString(new String(Base64.getDecoder().decode(in.value()), StandardCharsets.UTF_8)).getAsJsonObject();
		} else {
			root = new JsonObject();
			root.addProperty("timestamp", System.currentTimeMillis());
			root.addProperty("profileId", id.toString().replace("-", ""));
			root.addProperty("profileName", botName);
			root.add("textures", new JsonObject());
		}
		JsonObject textures = root.getAsJsonObject("textures");
		boolean slim = "alex".equals(model);
		JsonObject skin = textures.has("SKIN") ? textures.getAsJsonObject("SKIN") : new JsonObject();
		if (!skin.has("url") || in == null || in.value() == null) {
			skin.addProperty("url", slim ? ALEX_URL : STEVE_URL);
		}
		JsonObject meta = new JsonObject();
		if (slim) {
			meta.addProperty("model", "slim");
			skin.add("metadata", meta);
		} else {
			skin.remove("metadata");
		}
		textures.add("SKIN", skin);
		String value = Base64.getEncoder().encodeToString(root.toString().getBytes(StandardCharsets.UTF_8));
		// A modified property can no longer carry Mojang's signature.
		return new Fetched(in == null ? null : in.sourceName(), value, null);
	}

	public static GameProfile profile(UUID id, String name, BotSettings.Entry settings) {
		if (settings == null || settings.skinValue == null) {
			return new GameProfile(id, name);
		}
		Multimap<String, Property> props = HashMultimap.create();
		props.put("textures", settings.skinSignature == null
			? new Property("textures", settings.skinValue)
			: new Property("textures", settings.skinValue, settings.skinSignature));
		return new GameProfile(id, name, new PropertyMap(props));
	}
}
