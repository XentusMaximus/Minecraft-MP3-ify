package de.vibecoded.mp3player;

import de.vibecoded.mp3player.block.BoomboxBlock;
import de.vibecoded.mp3player.block.SpeakerBlock;
import de.vibecoded.mp3player.item.BoomboxItem;
import de.vibecoded.mp3player.item.Mp3PlayerItem;
import de.vibecoded.mp3player.item.SpeakerControllerItem;
import de.vibecoded.mp3player.item.SpeakerItem;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
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

	public static final ResourceKey<Block> BOOMBOX_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, id("boombox"));
	public static final BoomboxBlock BOOMBOX_BLOCK = Registry.register(
			BuiltInRegistries.BLOCK,
			BOOMBOX_BLOCK_KEY,
			new BoomboxBlock(BlockBehaviour.Properties.of()
					.setId(BOOMBOX_BLOCK_KEY)
					.mapColor(MapColor.COLOR_BLACK)
					.strength(1.2f, 4.0f)
					.sound(SoundType.METAL)
					.noOcclusion()
					.lightLevel(state -> state.getValue(BoomboxBlock.PLAYING) ? 4 : 0))
	);

	public static final ResourceKey<Item> BOOMBOX_ITEM_KEY = ResourceKey.create(Registries.ITEM, id("boombox"));
	public static final BoomboxItem BOOMBOX_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			BOOMBOX_ITEM_KEY,
			new BoomboxItem(BOOMBOX_BLOCK, new Item.Properties()
					.setId(BOOMBOX_ITEM_KEY)
					.useBlockDescriptionPrefix()
					.stacksTo(16)
					.rarity(Rarity.RARE))
	);

	public static final ResourceKey<Block> SPEAKER_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, id("speaker"));
	public static final SpeakerBlock SPEAKER_BLOCK = Registry.register(
			BuiltInRegistries.BLOCK,
			SPEAKER_BLOCK_KEY,
			new SpeakerBlock(BlockBehaviour.Properties.of()
					.setId(SPEAKER_BLOCK_KEY)
					.mapColor(MapColor.COLOR_BLACK)
					.strength(1.5f, 4.5f)
					.sound(SoundType.WOOD))
	);

	public static final ResourceKey<Item> SPEAKER_ITEM_KEY = ResourceKey.create(Registries.ITEM, id("speaker"));
	public static final SpeakerItem SPEAKER_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			SPEAKER_ITEM_KEY,
			new SpeakerItem(SPEAKER_BLOCK, new Item.Properties()
					.setId(SPEAKER_ITEM_KEY)
					.useBlockDescriptionPrefix()
					.stacksTo(64)
					.rarity(Rarity.UNCOMMON))
	);

	public static final ResourceKey<Item> SPEAKER_CONTROLLER_KEY = ResourceKey.create(Registries.ITEM, id("speaker_controller"));
	public static final SpeakerControllerItem SPEAKER_CONTROLLER_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			SPEAKER_CONTROLLER_KEY,
			new SpeakerControllerItem(new Item.Properties()
					.setId(SPEAKER_CONTROLLER_KEY)
					.stacksTo(1)
					.rarity(Rarity.RARE))
	);

	private static final ResourceKey<CreativeModeTab> TOOLS_TAB_KEY = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB,
			Identifier.withDefaultNamespace("tools_and_utilities")
	);
	private static final ResourceKey<CreativeModeTab> FUNCTIONAL_TAB_KEY = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB,
			Identifier.withDefaultNamespace("functional_blocks")
	);

	@Override
	public void onInitialize() {
		CreativeModeTabEvents.modifyOutputEvent(TOOLS_TAB_KEY).register(output -> {
			output.accept(MP3_PLAYER_ITEM);
			output.accept(SPEAKER_CONTROLLER_ITEM);
			output.accept(BOOMBOX_ITEM);
			output.accept(SPEAKER_ITEM);
		});
		CreativeModeTabEvents.modifyOutputEvent(FUNCTIONAL_TAB_KEY).register(output -> {
			output.accept(BOOMBOX_ITEM);
			output.accept(SPEAKER_ITEM);
			output.accept(SPEAKER_CONTROLLER_ITEM);
		});

		Mp3Payloads.registerPayloads();
		SpeakerRelayServer.init();
		LOGGER.info("MP3-Player, Boombox & Linked Speakers Mod v1.3.4 initialized for Minecraft 26.2!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
