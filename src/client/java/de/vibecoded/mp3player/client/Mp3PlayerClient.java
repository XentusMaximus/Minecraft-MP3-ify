package de.vibecoded.mp3player.client;

import com.mojang.blaze3d.platform.InputConstants;
import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.block.BoomboxBlock;
import de.vibecoded.mp3player.block.SpeakerBlock;
import de.vibecoded.mp3player.client.audio.Mp3AudioController;
import de.vibecoded.mp3player.client.audio.RemoteSpeakerManager;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.client.debug.TimeoutSimulationHelper;
import de.vibecoded.mp3player.client.gui.Mp3PlayerScreen;
import de.vibecoded.mp3player.client.gui.SoundOptionsInjector;
import de.vibecoded.mp3player.client.library.Mp3LibraryManager;
import de.vibecoded.mp3player.item.Mp3PlayerItem;
import de.vibecoded.mp3player.item.SpeakerControllerItem;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CQueuePlayTrigger;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CSpeakerQueueSync;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStop;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

public class Mp3PlayerClient implements ClientModInitializer {
	private static Mp3Config config;
	private static Mp3LibraryManager libraryManager;
	private static Mp3AudioController audioController;
	private static RemoteSpeakerManager remoteSpeakerManager;
	private static KeyMapping openPlayerKey;

	@Override
	public void onInitializeClient() {
		config = new Mp3Config();
		libraryManager = new Mp3LibraryManager();
		audioController = new Mp3AudioController(config, libraryManager);
		remoteSpeakerManager = new RemoteSpeakerManager(config);

		// Inject native MP3 & Boombox volume slider into Minecraft's Music & Sounds Options (0 Mixins)
		SoundOptionsInjector.register(config, audioController, remoteSpeakerManager);

		KeyMapping.Category category = KeyMapping.Category.register(Mp3PlayerMod.id("main"));
		openPlayerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mp3player.open",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_M,
				category
		));

		// Connect Handheld Item Right-Click & Shift+Right-Click to Client UI / Quick Toggle
		Mp3PlayerItem.clientUseCallback = shiftHeld -> {
			Minecraft mc = Minecraft.getInstance();
			if (shiftHeld) {
				handleQuickToggle(mc);
			} else {
				openPlayerScreen(mc);
			}
		};

		// Connect Placed Boombox Block Right-Click & Shift+Right-Click to Boombox UI / Quick Toggle
		BoomboxBlock.clientUseCallback = (level, pos, shiftHeld) -> {
			Minecraft mc = Minecraft.getInstance();
			if (shiftHeld) {
				handleBoomboxQuickToggle(mc, level.dimension(), pos);
			} else {
				openBoomboxScreen(mc, level.dimension(), pos);
			}
		};

		// Connect Placed Speaker Block Right-Click & Shift+Right-Click
		SpeakerBlock.clientUseCallback = (level, pos, shiftHeld) -> {
			Minecraft mc = Minecraft.getInstance();
			audioController.linkSpeaker(level.dimension(), pos);
			if (shiftHeld) {
				handleSpeakerControllerQuickToggle(mc);
			} else {
				openSpeakerControllerScreen(mc, pos);
			}
		};

		// Connect Speaker Controller Item (Link/Unlink on Block, Open Management GUI / Quick Toggle in Air)
		SpeakerControllerItem.linkedSpeakerCountSupplier = () -> audioController.activeLinkedSpeakerCount();

		SpeakerControllerItem.clientUseOnBlockCallback = (level, pos, state, shiftHeld) -> {
			Minecraft mc = Minecraft.getInstance();
			if (state.is(Mp3PlayerMod.SPEAKER_BLOCK)) {
				if (shiftHeld) {
					audioController.linkSpeaker(level.dimension(), pos);
					openSpeakerControllerScreen(mc, pos);
				} else {
					boolean linked = audioController.toggleSpeakerLink(level.dimension(), pos);
					if (mc.player != null) {
						String coords = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
						int total = audioController.totalLinkedSpeakerCount();
						String key = linked
								? "message.mp3player.speaker_linked"
								: "message.mp3player.speaker_unlinked";
						ChatFormatting color = linked ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
						mc.player.sendOverlayMessage(Component.translatable(key, coords, total).withStyle(color));
					}
				}
			} else if (state.is(Mp3PlayerMod.BOOMBOX_BLOCK)) {
				audioController.activateBoomboxTarget(level.dimension(), pos);
				openBoomboxScreen(mc, level.dimension(), pos);
			}
		};

		SpeakerControllerItem.clientUseCallback = shiftHeld -> {
			Minecraft mc = Minecraft.getInstance();
			if (shiftHeld) {
				handleSpeakerControllerQuickToggle(mc);
			} else {
				openSpeakerControllerScreen(mc, null);
			}
		};

		// Register S2C 3D speaker, Boombox & linked Speaker stream receivers (Essential & Multiplayer compatible)
		ClientPlayNetworking.registerGlobalReceiver(S2CStreamStart.TYPE, (payload, context) ->
				context.client().execute(() -> remoteSpeakerManager.onStreamStart(payload)));

		ClientPlayNetworking.registerGlobalReceiver(S2CStreamChunk.TYPE, (payload, context) ->
				context.client().execute(() -> remoteSpeakerManager.onStreamChunk(payload)));

		ClientPlayNetworking.registerGlobalReceiver(S2CStreamState.TYPE, (payload, context) ->
				context.client().execute(() -> {
					Minecraft mc = context.client();
					if (mc.player != null && mc.player.getUUID().equals(payload.speakerUuid())) {
						audioController.onServerUpdatedStreamState(payload);
					} else {
						remoteSpeakerManager.onStreamState(payload);
					}
				}));

		ClientPlayNetworking.registerGlobalReceiver(S2CStreamStop.TYPE, (payload, context) ->
				context.client().execute(() -> {
					Minecraft mc = context.client();
					if (mc.player != null && mc.player.getUUID().equals(payload.speakerUuid())) {
						audioController.onServerStoppedStream(payload.streamId());
					} else {
						remoteSpeakerManager.onStreamStop(payload);
					}
				}));

		ClientPlayNetworking.registerGlobalReceiver(S2CSpeakerQueueSync.TYPE, (payload, context) ->
				context.client().execute(() -> audioController.onServerQueueSync(payload)));

		ClientPlayNetworking.registerGlobalReceiver(S2CQueuePlayTrigger.TYPE, (payload, context) ->
				context.client().execute(() -> audioController.onServerQueuePlayTrigger(payload)));

		TimeoutSimulationHelper.registerCommands();

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			Runnable onJoin = () -> audioController.resetLinkedSpeakers();
			if (client.isSameThread()) {
				onJoin.run();
			} else {
				client.execute(onJoin);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			Runnable cleanup = () -> {
				TimeoutSimulationHelper.reset();
				audioController.onClientDisconnect();
				remoteSpeakerManager.stopAll();
			};
			if (client.isSameThread()) {
				cleanup.run();
			} else {
				client.execute(cleanup);
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			TimeoutSimulationHelper.reset();
			audioController.stop();
			audioController.resetLinkedSpeakers();
			remoteSpeakerManager.stopAll();
			config.save();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			TimeoutSimulationHelper.tick(client);

			while (openPlayerKey.consumeClick()) {
				if (client.player != null) {
					if (hasMp3PlayerAccess(client.player)) {
						if (isHoldingSpeakerController(client.player)) {
							openSpeakerControllerScreen(client, null);
						} else {
							openPlayerScreen(client);
						}
					} else {
						client.player.sendOverlayMessage(
								Component.translatable("message.mp3player.need_item")
										.withStyle(ChatFormatting.RED)
						);
					}
				}
			}

			audioController.tick(client);
			remoteSpeakerManager.tick(client);
		});
	}

	private static boolean isHoldingSpeakerController(LocalPlayer player) {
		return player.getMainHandItem().is(Mp3PlayerMod.SPEAKER_CONTROLLER_ITEM)
				|| player.getOffhandItem().is(Mp3PlayerMod.SPEAKER_CONTROLLER_ITEM);
	}

	private static boolean hasMp3PlayerAccess(LocalPlayer player) {
		if (player.isCreative() || player.isSpectator() || audioController.hasActiveTrack()) {
			return true;
		}
		return player.getInventory().contains(stack ->
				stack.is(Mp3PlayerMod.MP3_PLAYER_ITEM)
						|| stack.is(Mp3PlayerMod.BOOMBOX_ITEM)
						|| stack.is(Mp3PlayerMod.SPEAKER_CONTROLLER_ITEM)
						|| stack.is(Mp3PlayerMod.SPEAKER_ITEM));
	}

	private static void openPlayerScreen(Minecraft mc) {
		libraryManager.refresh();
		mc.gui.setScreen(new Mp3PlayerScreen(audioController, remoteSpeakerManager, openPlayerKey));
	}

	private static void openBoomboxScreen(Minecraft mc, ResourceKey<Level> dimension, BlockPos pos) {
		libraryManager.refresh();
		mc.gui.setScreen(new Mp3PlayerScreen(audioController, remoteSpeakerManager, openPlayerKey, dimension, pos));
	}

	private static void openSpeakerControllerScreen(Minecraft mc, BlockPos focusedSpeakerPos) {
		libraryManager.refresh();
		mc.gui.setScreen(new Mp3PlayerScreen(audioController, remoteSpeakerManager, openPlayerKey, true, focusedSpeakerPos));
	}

	private static void handleQuickToggle(Minecraft mc) {
		libraryManager.refresh();
		if (libraryManager.tracks().isEmpty() && !audioController.hasActiveTrack()) {
			if (mc.player != null) {
				mc.player.sendOverlayMessage(
						Component.translatable("message.mp3player.no_tracks")
								.withStyle(ChatFormatting.YELLOW)
				);
			}
			return;
		}
		audioController.togglePlayPause();
		Mp3TrackInfo track = audioController.currentTrack();
		if (mc.player != null && track != null) {
			String key = audioController.isPlaying() ? "message.mp3player.playing" : "message.mp3player.paused";
			ChatFormatting color = audioController.isPlaying() ? ChatFormatting.AQUA : ChatFormatting.GRAY;
			mc.player.sendOverlayMessage(
					Component.translatable(key, track.fullDisplayLabel()).withStyle(color)
			);
		}
	}

	private static void handleBoomboxQuickToggle(Minecraft mc, ResourceKey<Level> dimension, BlockPos pos) {
		libraryManager.refresh();
		if (libraryManager.tracks().isEmpty() && !audioController.hasActiveTrack()) {
			if (mc.player != null) {
				mc.player.sendOverlayMessage(
						Component.translatable("message.mp3player.no_tracks")
								.withStyle(ChatFormatting.YELLOW)
				);
			}
			return;
		}
		audioController.togglePlayPauseOnBoombox(dimension, pos);
		Mp3TrackInfo track = audioController.currentTrack();
		if (mc.player != null && track != null) {
			String key = audioController.isPlaying()
					? "message.mp3player.boombox_playing"
					: "message.mp3player.paused";
			ChatFormatting color = audioController.isPlaying() ? ChatFormatting.GREEN : ChatFormatting.GRAY;
			mc.player.sendOverlayMessage(
					Component.translatable(key, track.fullDisplayLabel()).withStyle(color)
			);
		}
	}

	private static void handleSpeakerControllerQuickToggle(Minecraft mc) {
		libraryManager.refresh();
		if (libraryManager.tracks().isEmpty() && !audioController.hasActiveTrack()) {
			if (mc.player != null) {
				mc.player.sendOverlayMessage(
						Component.translatable("message.mp3player.no_tracks")
								.withStyle(ChatFormatting.YELLOW)
				);
			}
			return;
		}
		if (audioController.totalLinkedSpeakerCount() == 0 && mc.level != null && mc.player != null) {
			audioController.scanAndLinkNearbySpeakers(
					mc.level,
					mc.player.blockPosition(),
					Mp3AudioController.DEFAULT_SPEAKER_SCAN_RADIUS
			);
		}
		boolean anotherWasPlaying = audioController.isAnotherPlayerPlayingOnSpeakers();
		audioController.togglePlayPauseOnSpeakers();
		Mp3TrackInfo track = audioController.currentTrack();
		if (mc.player != null) {
			if (anotherWasPlaying && track == null && !libraryManager.tracks().isEmpty()) {
				mc.player.sendOverlayMessage(
						Component.translatable("message.mp3player.queue_added_short")
								.withStyle(ChatFormatting.GOLD)
				);
			} else if (track != null) {
				String key = audioController.isPlaying()
						? "message.mp3player.speakers_playing"
						: "message.mp3player.paused";
				ChatFormatting color = audioController.isPlaying() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY;
				mc.player.sendOverlayMessage(
						Component.translatable(key, track.fullDisplayLabel(), audioController.activeLinkedSpeakerCount())
								.withStyle(color)
				);
			}
		}
	}
}
