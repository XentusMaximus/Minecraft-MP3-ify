package de.vibecoded.mp3player;

import de.vibecoded.mp3player.item.Mp3PlayerItem;
import de.vibecoded.mp3player.network.Mp3Payloads;
import de.vibecoded.mp3player.network.SpeakerRelayServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mp3PlayerMod implements ModInitializer {
	public static final String MOD_ID = "mp3player";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ResourceKey<Item> MP3_PLAYER_KEY = ResourceKey.create(Registries.ITEM, id("mp3_player"));
	public static final Mp3PlayerItem MP3_PLAYER_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			MP3_PLAYER_KEY,
			new Mp3PlayerItem(new Item.Properties()
					.setId(MP3_PLAYER_KEY)
					.stacksTo(1)
					.rarity(Rarity.RARE))
	);

	private static final ResourceKey<CreativeModeTab> TOOLS_TAB_KEY = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB,
			Identifier.withDefaultNamespace("tools_and_utilities")
	);

	@Override
	public void onInitialize() {
		CreativeModeTabEvents.modifyOutputEvent(TOOLS_TAB_KEY).register(output -> output.accept(MP3_PLAYER_ITEM));
		Mp3Payloads.registerPayloads();
		SpeakerRelayServer.init();
		LOGGER.info("MP3-Player Mod initialized for Minecraft 26.2!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

