package de.vibecoded.mp3player.client;

import com.mojang.blaze3d.platform.InputConstants;
import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.client.audio.Mp3AudioController;
import de.vibecoded.mp3player.client.audio.RemoteSpeakerManager;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.client.debug.TimeoutSimulationHelper;
import de.vibecoded.mp3player.client.gui.Mp3PlayerScreen;
import de.vibecoded.mp3player.client.library.Mp3LibraryManager;
import de.vibecoded.mp3player.item.Mp3PlayerItem;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
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
import net.minecraft.network.chat.Component;
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

		KeyMapping.Category category = KeyMapping.Category.register(Mp3PlayerMod.id("main"));
		openPlayerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mp3player.open",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_M,
				category
		));

		// Connect Item Right-Click & Shift+Right-Click to Client UI / Quick Toggle
		Mp3PlayerItem.clientUseCallback = shiftHeld -> {
			Minecraft mc = Minecraft.getInstance();
			if (shiftHeld) {
				handleQuickToggle(mc);
			} else {
				openPlayerScreen(mc);
			}
		};

		// Register S2C 3D speaker stream receivers (Essential & Multiplayer compatible)
		ClientPlayNetworking.registerGlobalReceiver(S2CStreamStart.TYPE, (payload, context) ->
				context.client().execute(() -> remoteSpeakerManager.onStreamStart(payload)));

		ClientPlayNetworking.registerGlobalReceiver(S2CStreamChunk.TYPE, (payload, context) ->
				context.client().execute(() -> remoteSpeakerManager.onStreamChunk(payload)));

		ClientPlayNetworking.registerGlobalReceiver(S2CStreamState.TYPE, (payload, context) ->
				context.client().execute(() -> remoteSpeakerManager.onStreamState(payload)));

		ClientPlayNetworking.registerGlobalReceiver(S2CStreamStop.TYPE, (payload, context) ->
				context.client().execute(() -> remoteSpeakerManager.onStreamStop(payload)));

		TimeoutSimulationHelper.registerCommands();

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
			remoteSpeakerManager.stopAll();
			config.save();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			TimeoutSimulationHelper.tick(client);

			while (openPlayerKey.consumeClick()) {
				if (client.player != null) {
					if (hasMp3PlayerAccess(client.player)) {
						openPlayerScreen(client);
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

	private static boolean hasMp3PlayerAccess(LocalPlayer player) {
		if (player.isCreative() || player.isSpectator()) {
			return true;
		}
		return player.getInventory().contains(stack -> stack.is(Mp3PlayerMod.MP3_PLAYER_ITEM));
	}

	private static void openPlayerScreen(Minecraft mc) {
		libraryManager.refresh();
		mc.gui.setScreen(new Mp3PlayerScreen(audioController, remoteSpeakerManager, openPlayerKey));
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
}
