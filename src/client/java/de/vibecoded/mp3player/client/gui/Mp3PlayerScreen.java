package de.vibecoded.mp3player.client.gui;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.client.audio.Mp3AudioController;
import de.vibecoded.mp3player.client.audio.RemoteSpeakerManager;
import de.vibecoded.mp3player.client.audio.RemoteSpeakerManager.RemoteStream;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.client.config.Mp3Config.LinkedSpeaker;
import de.vibecoded.mp3player.client.config.Mp3Config.LoopMode;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

public class Mp3PlayerScreen extends Screen {
	private static final int ROW_HEIGHT = 22;

	private enum ViewTab {
		TRACKS,
		SPEAKERS
	}

	private final Mp3AudioController controller;
	private final RemoteSpeakerManager remoteSpeakers;
	private final KeyMapping toggleKey;
	private final ResourceKey<Level> targetBoomboxDimension;
	private final BlockPos targetBoomboxPos;
	private final boolean speakerControllerScreen;
	private final BlockPos focusedSpeakerPos;
	private final ItemStack iconStack;

	private ViewTab viewTab;

	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int listLeft;
	private int listTop;
	private int listRight;
	private int listBottom;
	private int deckTop;
	private int seekLeft;
	private int seekRight;
	private int seekBarY;

	private EditBox searchBox;
	private Button folderBtn;
	private Button refreshBtn;

	private Button tracksSwitchBtn;
	private Button speakerStreamsBtn;
	private Button scanSpeakersBtn;
	private Button clearSpeakersBtn;

	private Button speakersHeaderBtn;
	private Button modeBtn;
	private Button shuffleBtn;
	private Button playPauseBtn;
	private Button loopBtn;
	private Button muteOthersBtn;

	private String searchQuery = "";
	private List<Mp3TrackInfo> filteredTracks = List.of();
	private List<LinkedSpeaker> currentSpeakers = List.of();
	private double scrollOffset = 0.0;
	private double speakerScrollOffset = 0.0;
	private boolean draggingSeek = false;
	private double dragSeekRatio = 0.0;
	private boolean draggingScrollbar = false;

	private String toastMessage = null;
	private long toastExpireTimeMs = 0L;

	public Mp3PlayerScreen(Mp3AudioController controller, RemoteSpeakerManager remoteSpeakers, KeyMapping toggleKey) {
		this(controller, remoteSpeakers, toggleKey, null, null, false, null);
	}

	public Mp3PlayerScreen(
			Mp3AudioController controller,
			RemoteSpeakerManager remoteSpeakers,
			KeyMapping toggleKey,
			ResourceKey<Level> targetBoomboxDimension,
			BlockPos targetBoomboxPos
	) {
		this(controller, remoteSpeakers, toggleKey, targetBoomboxDimension, targetBoomboxPos, false, null);
	}

	public Mp3PlayerScreen(
			Mp3AudioController controller,
			RemoteSpeakerManager remoteSpeakers,
			KeyMapping toggleKey,
			boolean speakerControllerScreen,
			BlockPos focusedSpeakerPos
	) {
		this(controller, remoteSpeakers, toggleKey, null, null, speakerControllerScreen, focusedSpeakerPos);
	}

	private Mp3PlayerScreen(
			Mp3AudioController controller,
			RemoteSpeakerManager remoteSpeakers,
			KeyMapping toggleKey,
			ResourceKey<Level> targetBoomboxDimension,
			BlockPos targetBoomboxPos,
			boolean speakerControllerScreen,
			BlockPos focusedSpeakerPos
	) {
		super(Component.translatable(
				targetBoomboxPos != null
						? "block.mp3player.boombox"
						: (speakerControllerScreen ? "item.mp3player.speaker_controller" : "screen.mp3player.title")
		));
		this.controller = controller;
		this.remoteSpeakers = remoteSpeakers;
		this.toggleKey = toggleKey;
		this.targetBoomboxDimension = targetBoomboxDimension;
		this.targetBoomboxPos = targetBoomboxPos != null ? targetBoomboxPos.immutable() : null;
		this.speakerControllerScreen = speakerControllerScreen;
		this.focusedSpeakerPos = focusedSpeakerPos != null ? focusedSpeakerPos.immutable() : null;
		this.viewTab = speakerControllerScreen ? ViewTab.SPEAKERS : ViewTab.TRACKS;
		if (targetBoomboxPos != null) {
			this.iconStack = new ItemStack(Mp3PlayerMod.BOOMBOX_ITEM);
		} else if (speakerControllerScreen) {
			this.iconStack = new ItemStack(Mp3PlayerMod.SPEAKER_CONTROLLER_ITEM);
		} else {
			this.iconStack = new ItemStack(Mp3PlayerMod.MP3_PLAYER_ITEM);
		}
	}

	private boolean isBoomboxScreen() {
		return targetBoomboxPos != null && targetBoomboxDimension != null;
	}

	@Override
	protected void init() {
		this.panelW = Math.min(368, Math.max(310, this.width - 24));
		this.panelH = Math.min(278, Math.max(220, this.height - 16));
		this.panelX = (this.width - this.panelW) / 2;
		this.panelY = (this.height - this.panelH) / 2;

		this.listLeft = panelX + 8;
		this.listRight = panelX + panelW - 8;
		this.listTop = panelY + 53;
		this.deckTop = panelY + panelH - 68;
		this.listBottom = deckTop - 4;

		this.seekLeft = panelX + 38;
		this.seekRight = panelX + panelW - 38;
		this.seekBarY = deckTop + 19;

		updateFilteredList();
		updateSpeakerList();

		// 1. Header Tab Toggle (Speakers badge) + Output Mode Button
		this.speakersHeaderBtn = Button.builder(buildSpeakersHeaderLabel(), btn -> {
			this.viewTab = (this.viewTab == ViewTab.SPEAKERS) ? ViewTab.TRACKS : ViewTab.SPEAKERS;
			syncTabVisibility();
			refreshButtonLabels();
		}).bounds(panelX + panelW - 192, panelY + 5, 48, 18).build();
		this.addRenderableWidget(this.speakersHeaderBtn);

		this.modeBtn = Button.builder(buildModeLabel(), btn -> {
			handleModeButtonClick();
			refreshButtonLabels();
		}).bounds(panelX + panelW - 140, panelY + 5, 132, 18).build();
		this.addRenderableWidget(this.modeBtn);

		// 2A. TRACKS Tab Toolbar: Search Bar + Folder & Refresh Buttons
		int searchWidth = panelW - 108;
		this.searchBox = new EditBox(
				this.font,
				panelX + 8,
				panelY + 30,
				searchWidth,
				18,
				Component.translatable("screen.mp3player.search_hint")
		);
		this.searchBox.setMaxLength(80);
		this.searchBox.setHint(Component.translatable("screen.mp3player.search_hint")
				.withStyle(ChatFormatting.DARK_GRAY));
		this.searchBox.setValue(searchQuery);
		this.searchBox.setResponder(val -> {
			this.searchQuery = val;
			this.scrollOffset = 0.0;
			updateFilteredList();
		});
		this.addRenderableWidget(this.searchBox);

		this.folderBtn = Button.builder(Component.translatable("screen.mp3player.open_folder"), btn -> {
			controller.library().ensureDirectoryExists();
			Util.getPlatform().openPath(controller.library().mp3Directory());
		}).bounds(panelX + panelW - 96, panelY + 30, 56, 18).build();
		this.folderBtn.setTooltip(Tooltip.create(Component.literal(controller.library().mp3Directory().toString())));
		this.addRenderableWidget(this.folderBtn);

		this.refreshBtn = Button.builder(Component.literal("⟳"), btn -> {
			controller.library().refresh();
			updateFilteredList();
			refreshButtonLabels();
		}).bounds(panelX + panelW - 36, panelY + 30, 28, 18).build();
		this.refreshBtn.setTooltip(Tooltip.create(Component.translatable("screen.mp3player.refresh")));
		this.addRenderableWidget(this.refreshBtn);

		// 2B. SPEAKERS Tab Toolbar: Songs Tab Switch + Streams ON/OFF + Scan Nearby + Unlink All
		this.tracksSwitchBtn = Button.builder(buildTracksSwitchLabel(), btn -> {
			this.viewTab = ViewTab.TRACKS;
			syncTabVisibility();
			refreshButtonLabels();
		}).bounds(panelX + 8, panelY + 30, 96, 18).build();
		this.tracksSwitchBtn.setTooltip(Tooltip.create(Component.translatable("screen.mp3player.speakers.switch_tracks_tooltip")));
		this.addRenderableWidget(this.tracksSwitchBtn);

		int midW = Math.max(90, panelW - 8 - 96 - 4 - 84 - 4 - 28 - 8);
		this.speakerStreamsBtn = Button.builder(buildSpeakerStreamsLabel(), btn -> {
			controller.setLinkedSpeakersEnabled(!controller.config().linkedSpeakersEnabled());
			updateSpeakerList();
			refreshButtonLabels();
		}).bounds(panelX + 108, panelY + 30, midW, 18).build();
		this.addRenderableWidget(this.speakerStreamsBtn);

		this.scanSpeakersBtn = Button.builder(Component.translatable("screen.mp3player.speakers.scan"), btn -> {
			if (this.minecraft != null && this.minecraft.level != null && this.minecraft.player != null) {
				int added = controller.scanAndLinkNearbySpeakers(
						this.minecraft.level,
						this.minecraft.player.blockPosition(),
						Mp3AudioController.DEFAULT_SPEAKER_SCAN_RADIUS
				);
				updateSpeakerList();
				refreshButtonLabels();
				this.toastMessage = Component.translatable("screen.mp3player.speakers.scanned_toast", added).getString();
				this.toastExpireTimeMs = System.currentTimeMillis() + 3000L;
			}
		}).bounds(panelX + panelW - 120, panelY + 30, 80, 18).build();
		this.scanSpeakersBtn.setTooltip(Tooltip.create(Component.translatable("screen.mp3player.speakers.scan_tooltip")));
		this.addRenderableWidget(this.scanSpeakersBtn);

		this.clearSpeakersBtn = Button.builder(Component.literal("🗑"), btn -> {
			ResourceKey<Level> dim = (this.minecraft != null && this.minecraft.level != null)
					? this.minecraft.level.dimension()
					: null;
			controller.clearLinkedSpeakers(dim);
			updateSpeakerList();
			refreshButtonLabels();
		}).bounds(panelX + panelW - 36, panelY + 30, 28, 18).build();
		this.clearSpeakersBtn.setTooltip(Tooltip.create(Component.translatable("screen.mp3player.speakers.clear_all")));
		this.addRenderableWidget(this.clearSpeakersBtn);

		// 3. Transport Controls + Volume Slider on Bottom Deck
		int controlsY = deckTop + 38;
		int cx = panelX + 8;

		this.shuffleBtn = Button.builder(buildShuffleLabel(), btn -> {
			Mp3Config cfg = controller.config();
			cfg.setShuffle(!cfg.shuffle());
			cfg.save();
			refreshButtonLabels();
		}).bounds(cx, controlsY, 24, 18).build();
		this.addRenderableWidget(this.shuffleBtn);
		cx += 27;

		Button prevBtn = Button.builder(Component.literal("⏮"), btn -> {
			if (isBoomboxScreen() && !controller.isTargetingBoombox(targetBoomboxDimension, targetBoomboxPos)) {
				controller.activateBoomboxTarget(targetBoomboxDimension, targetBoomboxPos);
			} else if (speakerControllerScreen && !controller.isBoomboxActive() && !controller.isSpeakerSystemMode()) {
				controller.setSpeakerSystemMode(true);
			}
			controller.previousTrack();
		}).bounds(cx, controlsY, 24, 18).build();
		this.addRenderableWidget(prevBtn);
		cx += 27;

		this.playPauseBtn = Button.builder(buildPlayPauseLabel(), btn -> {
			handlePlayPauseAction();
			refreshButtonLabels();
		}).bounds(cx, controlsY, 32, 18).build();
		this.addRenderableWidget(this.playPauseBtn);
		cx += 35;

		Button stopBtn = Button.builder(Component.literal("⏹"), btn -> {
			controller.stop();
			refreshButtonLabels();
		}).bounds(cx, controlsY, 24, 18).build();
		this.addRenderableWidget(stopBtn);
		cx += 27;

		Button nextBtn = Button.builder(Component.literal("⏭"), btn -> {
			if (isBoomboxScreen() && !controller.isTargetingBoombox(targetBoomboxDimension, targetBoomboxPos)) {
				controller.activateBoomboxTarget(targetBoomboxDimension, targetBoomboxPos);
			} else if (speakerControllerScreen && !controller.isBoomboxActive() && !controller.isSpeakerSystemMode()) {
				controller.setSpeakerSystemMode(true);
			}
			controller.nextTrack();
		}).bounds(cx, controlsY, 24, 18).build();
		this.addRenderableWidget(nextBtn);
		cx += 27;

		this.loopBtn = Button.builder(buildLoopLabel(), btn -> {
			Mp3Config cfg = controller.config();
			cfg.setLoopMode(cfg.loopMode().next());
			cfg.save();
			refreshButtonLabels();
		}).bounds(cx, controlsY, 62, 18).build();
		this.addRenderableWidget(this.loopBtn);
		cx += 65;

		this.muteOthersBtn = Button.builder(buildMuteOthersLabel(), btn -> {
			Mp3Config cfg = controller.config();
			cfg.setMuteOthers(!cfg.muteOthers());
			cfg.save();
			refreshButtonLabels();
		}).bounds(cx, controlsY, 24, 18).build();
		this.addRenderableWidget(this.muteOthersBtn);
		cx += 27;

		int sliderW = Math.max(64, (panelX + panelW - 8) - cx);
		VolumeSlider volumeSlider = new VolumeSlider(cx, controlsY, sliderW, 18, controller);
		this.addRenderableWidget(volumeSlider);

		syncTabVisibility();
		refreshButtonLabels();
	}

	private void syncTabVisibility() {
		boolean tracksTab = (this.viewTab == ViewTab.TRACKS);
		if (searchBox != null) {
			searchBox.visible = tracksTab;
			searchBox.active = tracksTab;
			if (!tracksTab) {
				searchBox.setFocused(false);
			}
		}
		if (folderBtn != null) {
			folderBtn.visible = tracksTab;
			folderBtn.active = tracksTab;
		}
		if (refreshBtn != null) {
			refreshBtn.visible = tracksTab;
			refreshBtn.active = tracksTab;
		}

		boolean speakersTab = (this.viewTab == ViewTab.SPEAKERS);
		if (tracksSwitchBtn != null) {
			tracksSwitchBtn.visible = speakersTab;
			tracksSwitchBtn.active = speakersTab;
		}
		if (speakerStreamsBtn != null) {
			speakerStreamsBtn.visible = speakersTab;
			speakerStreamsBtn.active = speakersTab;
		}
		if (scanSpeakersBtn != null) {
			scanSpeakersBtn.visible = speakersTab;
			scanSpeakersBtn.active = speakersTab;
		}
		if (clearSpeakersBtn != null) {
			clearSpeakersBtn.visible = speakersTab;
			clearSpeakersBtn.active = speakersTab;
		}
	}

	private void handleModeButtonClick() {
		if (isBoomboxScreen()) {
			if (controller.isTargetingBoombox(targetBoomboxDimension, targetBoomboxPos)) {
				controller.deactivateBoomboxTarget();
			} else {
				controller.activateBoomboxTarget(targetBoomboxDimension, targetBoomboxPos);
			}
			return;
		}
		if (controller.isBoomboxActive()) {
			controller.deactivateBoomboxTarget();
			return;
		}
		if (controller.isSpeakerSystemMode()) {
			controller.setSpeakerSystemMode(false);
			controller.setSpeaker3dMode(false);
			return;
		}
		if (!controller.config().speaker3dMode()) {
			if (speakerControllerScreen || controller.totalLinkedSpeakerCount() > 0) {
				controller.setSpeakerSystemMode(true);
			} else {
				controller.setSpeaker3dMode(true);
			}
			return;
		}
		controller.setSpeaker3dMode(false);
	}

	private void handlePlayPauseAction() {
		if (isBoomboxScreen()) {
			controller.togglePlayPauseOnBoombox(targetBoomboxDimension, targetBoomboxPos);
		} else if (speakerControllerScreen && !controller.isBoomboxActive()) {
			controller.togglePlayPauseOnSpeakers();
		} else {
			controller.togglePlayPause();
		}
	}

	private void updateFilteredList() {
		this.filteredTracks = controller.library().filter(this.searchQuery);
		clampScroll();
	}

	private void updateSpeakerList() {
		this.currentSpeakers = controller.linkedSpeakersInCurrentLevel();
		clampSpeakerScroll();
	}

	private void refreshButtonLabels() {
		if (speakersHeaderBtn != null) {
			speakersHeaderBtn.setMessage(buildSpeakersHeaderLabel());
			speakersHeaderBtn.setTooltip(Tooltip.create(Component.translatable(
					viewTab == ViewTab.SPEAKERS
							? "screen.mp3player.speakers.switch_tracks_tooltip"
							: "screen.mp3player.speakers.open_tab_tooltip",
					controller.activeLinkedSpeakerCount(),
					controller.totalLinkedSpeakerCount()
			)));
		}
		if (tracksSwitchBtn != null) {
			tracksSwitchBtn.setMessage(buildTracksSwitchLabel());
		}
		if (speakerStreamsBtn != null) {
			speakerStreamsBtn.setMessage(buildSpeakerStreamsLabel());
			speakerStreamsBtn.setTooltip(Tooltip.create(Component.translatable(
					"screen.mp3player.speakers.streams_tooltip"
			)));
		}
		if (modeBtn != null) {
			modeBtn.setMessage(buildModeLabel());
			modeBtn.setTooltip(buildModeTooltip());
		}
		if (shuffleBtn != null) {
			shuffleBtn.setMessage(buildShuffleLabel());
			shuffleBtn.setTooltip(Tooltip.create(Component.translatable(
					controller.config().shuffle() ? "screen.mp3player.shuffle.on" : "screen.mp3player.shuffle.off"
			)));
		}
		if (playPauseBtn != null) {
			playPauseBtn.setMessage(buildPlayPauseLabel());
		}
		if (loopBtn != null) {
			loopBtn.setMessage(buildLoopLabel());
		}
		if (muteOthersBtn != null) {
			muteOthersBtn.setMessage(buildMuteOthersLabel());
			muteOthersBtn.setTooltip(Tooltip.create(Component.translatable(
					controller.config().muteOthers() ? "screen.mp3player.mute_others.on" : "screen.mp3player.mute_others.off"
			)));
		}
	}

	private Component buildSpeakersHeaderLabel() {
		int active = controller.activeLinkedSpeakerCount();
		int total = controller.totalLinkedSpeakerCount();
		ChatFormatting color;
		if (viewTab == ViewTab.SPEAKERS) {
			color = ChatFormatting.LIGHT_PURPLE;
		} else if (active > 0) {
			color = ChatFormatting.GREEN;
		} else if (total > 0) {
			color = ChatFormatting.YELLOW;
		} else {
			color = ChatFormatting.GRAY;
		}
		return Component.literal("🔊 " + active + "/" + total).withStyle(color);
	}

	private Component buildTracksSwitchLabel() {
		return Component.literal("🎵 ")
				.withStyle(ChatFormatting.AQUA)
				.append(Component.translatable("screen.mp3player.speakers.tracks_btn", controller.library().tracks().size())
						.withStyle(ChatFormatting.AQUA));
	}

	private Component buildSpeakerStreamsLabel() {
		boolean on = controller.config().linkedSpeakersEnabled();
		return Component.translatable(on ? "screen.mp3player.speakers.streams_on" : "screen.mp3player.speakers.streams_off")
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private Component buildModeLabel() {
		if (isBoomboxScreen()) {
			if (controller.isTargetingBoombox(targetBoomboxDimension, targetBoomboxPos)) {
				return Component.literal("📻 " + formatPosShort(targetBoomboxPos)).withStyle(ChatFormatting.GREEN);
			}
			return Component.literal("📻 ").withStyle(ChatFormatting.GOLD)
					.append(Component.translatable("screen.mp3player.mode.boombox_here").withStyle(ChatFormatting.GOLD));
		}
		if (controller.isBoomboxActive()) {
			return Component.literal("📻 " + formatPosShort(controller.activeBoomboxPos())).withStyle(ChatFormatting.GREEN);
		}
		if (controller.isSpeakerSystemMode()) {
			return Component.literal("🔊 ").withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(Component.translatable("screen.mp3player.mode.speaker_network").withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		if (controller.config().speaker3dMode()) {
			return Component.literal("🔊 ").withStyle(ChatFormatting.GREEN)
					.append(Component.translatable("screen.mp3player.mode.speaker3d").withStyle(ChatFormatting.GREEN));
		}
		return Component.literal("🎧 ").withStyle(ChatFormatting.AQUA)
				.append(Component.translatable("screen.mp3player.mode.headphones").withStyle(ChatFormatting.AQUA));
	}

	private Tooltip buildModeTooltip() {
		if (isBoomboxScreen()) {
			if (controller.isTargetingBoombox(targetBoomboxDimension, targetBoomboxPos)) {
				return Tooltip.create(Component.translatable("screen.mp3player.tooltip.boombox_active", formatPosShort(targetBoomboxPos)));
			}
			return Tooltip.create(Component.translatable("screen.mp3player.tooltip.boombox_transfer", formatPosShort(targetBoomboxPos)));
		}
		if (controller.isBoomboxActive()) {
			return Tooltip.create(Component.translatable("screen.mp3player.tooltip.boombox_active", formatPosShort(controller.activeBoomboxPos())));
		}
		if (controller.isSpeakerSystemMode()) {
			return Tooltip.create(Component.translatable(
					"screen.mp3player.tooltip.speaker_network",
					controller.activeLinkedSpeakerCount()
			));
		}
		if (controller.config().speaker3dMode() && !controller.isServerSupporting3D()) {
			return Tooltip.create(Component.translatable("screen.mp3player.server_local_only")
					.withStyle(ChatFormatting.YELLOW));
		}
		return null;
	}

	private static String formatPosShort(BlockPos pos) {
		if (pos == null) {
			return "";
		}
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	private Component buildShuffleLabel() {
		return Component.literal("🔀").withStyle(
				controller.config().shuffle() ? ChatFormatting.AQUA : ChatFormatting.GRAY
		);
	}

	private Component buildPlayPauseLabel() {
		boolean activeHere = !isBoomboxScreen() || controller.isTargetingBoombox(targetBoomboxDimension, targetBoomboxPos);
		boolean playingHere = activeHere && controller.isPlaying();
		return Component.literal(playingHere ? "⏸" : "▶")
				.withStyle(playingHere ? ChatFormatting.AQUA : ChatFormatting.WHITE);
	}

	private Component buildLoopLabel() {
		LoopMode mode = controller.config().loopMode();
		return switch (mode) {
			case OFF -> Component.translatable("screen.mp3player.loop.off").withStyle(ChatFormatting.GRAY);
			case ALL -> Component.translatable("screen.mp3player.loop.all").withStyle(ChatFormatting.AQUA);
			case ONE -> Component.translatable("screen.mp3player.loop.one").withStyle(ChatFormatting.GOLD);
		};
	}

	private Component buildMuteOthersLabel() {
		return Component.literal(controller.config().muteOthers() ? "🔇" : "🔉")
				.withStyle(controller.config().muteOthers() ? ChatFormatting.RED : ChatFormatting.GREEN);
	}

	@Override
	public void tick() {
		super.tick();
		if (isBoomboxScreen() && this.minecraft != null && this.minecraft.level != null) {
			if (!this.minecraft.level.dimension().equals(targetBoomboxDimension)
					|| (this.minecraft.level.isLoaded(targetBoomboxPos)
					&& !this.minecraft.level.getBlockState(targetBoomboxPos).is(Mp3PlayerMod.BOOMBOX_BLOCK))
					|| (this.minecraft.player != null
					&& targetBoomboxPos.distToCenterSqr(this.minecraft.player.position()) > 16.0 * 16.0)) {
				this.onClose();
				return;
			}
		}
		updateSpeakerList();
		refreshButtonLabels();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		boolean boomboxUi = isBoomboxScreen();
		boolean speakerUi = speakerControllerScreen || viewTab == ViewTab.SPEAKERS;

		// 1. Main Hi-Fi Window Card + Drop Shadow + Border
		graphics.fill(panelX + 3, panelY + 3, panelX + panelW + 3, panelY + panelH + 3, 0x66000000);
		graphics.fillGradient(panelX, panelY, panelX + panelW, panelY + panelH, 0xF2111827, 0xF50B1120);
		int borderColor = boomboxUi ? 0xFF10B981 : (speakerUi ? 0xFF8B5CF6 : 0xFF334155);
		graphics.outline(panelX, panelY, panelW, panelH, borderColor);

		// Header separator & subtle gradient bar
		int headerTopGrad = boomboxUi ? 0x88064E3B : (speakerUi ? 0x883B0764 : 0x881E293B);
		int headerLineCol = boomboxUi ? 0xFF065F46 : (speakerUi ? 0xFF581C87 : 0xFF1E293B);
		graphics.fillGradient(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 27, headerTopGrad, 0x220F172A);
		graphics.horizontalLine(panelX + 1, panelX + panelW - 2, panelY + 27, headerLineCol);

		// Header Icon + Title + Count Badge
		graphics.fakeItem(iconStack, panelX + 7, panelY + 5);
		String titleKey = boomboxUi
				? "block.mp3player.boombox"
				: (speakerUi ? "item.mp3player.speaker_controller" : "screen.mp3player.title");
		String titleStr = Component.translatable(titleKey).getString();
		int titleColor = boomboxUi ? 0xFFA7F3D0 : (speakerUi ? 0xFFDDD6FE : 0xFFF8FAFC);
		graphics.text(this.font, titleStr, panelX + 27, panelY + 10, titleColor, true);

		String countBadge = viewTab == ViewTab.SPEAKERS
				? "(" + currentSpeakers.size() + ")"
				: "(" + controller.library().tracks().size() + ")";
		graphics.text(this.font, countBadge, panelX + 27 + this.font.width(titleStr) + 5, panelY + 10, 0xFF64748B, false);

		// 2. Center Recessed Box (Playlist or Linked Speakers Management)
		graphics.fill(listLeft, listTop, listRight, listBottom, 0xCC070B14);
		graphics.outline(listLeft, listTop, listRight - listLeft, listBottom - listTop, 0xFF1E293B);

		if (viewTab == ViewTab.SPEAKERS) {
			renderSpeakerList(graphics, mouseX, mouseY);
		} else {
			renderTrackList(graphics, mouseX, mouseY);
		}

		// 3. Bottom Transport Deck Background
		graphics.fillGradient(panelX + 1, deckTop, panelX + panelW - 1, panelY + panelH - 1, 0xAA0F172A, 0xEE090D16);
		graphics.horizontalLine(panelX + 1, panelX + panelW - 2, deckTop, 0xFF1E293B);

		renderNowPlayingAndSeekBar(graphics, mouseX, mouseY);

		// 4. Optional Toast Notification (e.g., Drag & Drop import or Speaker Scan)
		if (toastMessage != null) {
			if (System.currentTimeMillis() < toastExpireTimeMs) {
				int tw = this.font.width(toastMessage) + 16;
				int tx = panelX + (panelW - tw) / 2;
				int ty = listBottom - 20;
				graphics.fill(tx, ty, tx + tw, ty + 16, 0xEE065F46);
				graphics.outline(tx, ty, tw, 16, 0xFF34D399);
				graphics.centeredText(this.font, toastMessage, panelX + panelW / 2, ty + 4, 0xFFECFDF5);
			} else {
				toastMessage = null;
			}
		}

		// 5. Render interactive widgets on top
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void renderTrackList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int innerLeft = listLeft + 1;
		int innerTop = listTop + 1;
		int innerRight = listRight - 1;
		int innerBottom = listBottom - 1;
		int viewHeight = innerBottom - innerTop;

		if (controller.library().tracks().isEmpty()) {
			int cy = innerTop + viewHeight / 2 - 10;
			graphics.centeredText(
					this.font,
					Component.translatable("screen.mp3player.empty.title"),
					panelX + panelW / 2,
					cy,
					0xFFE2E8F0
			);
			graphics.centeredText(
					this.font,
					Component.translatable("screen.mp3player.empty.subtitle"),
					panelX + panelW / 2,
					cy + 13,
					0xFF64748B
			);
			return;
		}

		if (filteredTracks.isEmpty()) {
			graphics.centeredText(
					this.font,
					Component.translatable("screen.mp3player.no_match"),
					panelX + panelW / 2,
					innerTop + viewHeight / 2 - 4,
					0xFF94A3B8
			);
			return;
		}

		Mp3TrackInfo activeTrack = controller.currentTrack();
		boolean inListBounds = mouseX >= innerLeft && mouseX < innerRight - 5 && mouseY >= innerTop && mouseY < innerBottom;

		graphics.enableScissor(innerLeft, innerTop, innerRight, innerBottom);
		long animTick = System.currentTimeMillis();

		for (int i = 0; i < filteredTracks.size(); i++) {
			int rowTop = (int) Math.round(innerTop + i * ROW_HEIGHT - scrollOffset);
			int rowBottom = rowTop + ROW_HEIGHT;
			if (rowBottom < innerTop || rowTop > innerBottom) {
				continue;
			}

			Mp3TrackInfo track = filteredTracks.get(i);
			boolean isCurrent = activeTrack != null && activeTrack.file().equals(track.file());
			boolean isHovered = inListBounds && mouseY >= rowTop && mouseY < rowBottom;

			if (isCurrent) {
				graphics.fill(innerLeft, rowTop, innerRight - 5, rowBottom - 1, 0x440EA5E9);
				graphics.fill(innerLeft, rowTop, innerLeft + 3, rowBottom - 1, 0xFF38BDF8);
			} else if (isHovered) {
				graphics.fill(innerLeft, rowTop, innerRight - 5, rowBottom - 1, 0x2A475569);
			} else if ((i & 1) == 1) {
				graphics.fill(innerLeft, rowTop, innerRight - 5, rowBottom - 1, 0x121E293B);
			}

			// Left indicator: Animated Equalizer Bars if playing, Pause icon, or Track Index
			int iconX = innerLeft + 7;
			if (isCurrent) {
				if (controller.isPlaying()) {
					float level = Math.max(0.18f, controller.visualizerLevel());
					for (int b = 0; b < 4; b++) {
						double wave = 0.55 + 0.45 * Math.sin((animTick / 95.0) + b * 1.45);
						int barH = Math.max(2, Math.min(12, (int) Math.round(12 * level * wave)));
						int bx = iconX + b * 3;
						int by = rowTop + 16 - barH;
						graphics.fill(bx, by, bx + 2, rowTop + 16, 0xFF38BDF8);
					}
				} else {
					graphics.text(this.font, "⏸", iconX + 2, rowTop + 7, 0xFF38BDF8, false);
				}
			} else {
				String num = String.format(Locale.ROOT, "%02d", i + 1);
				graphics.text(this.font, num, iconX, rowTop + 7, 0xFF475569, false);
			}

			// Duration right-aligned + compact Format Badge pill
			String dur = track.formattedDuration();
			int durW = this.font.width(dur);
			int durX = innerRight - 10 - durW;
			graphics.text(this.font, dur, durX, rowTop + 7, isCurrent ? 0xFF7DD3FC : 0xFF64748B, false);

			String badge = track.formatBadge();
			int badgeTextW = this.font.width(badge);
			int badgeBoxW = badgeTextW + 6;
			int badgeX = durX - 6 - badgeBoxW;
			int badgeY = rowTop + 5;
			int badgeColor = formatBadgeColor(badge, isCurrent);
			graphics.fill(badgeX, badgeY, badgeX + badgeBoxW, badgeY + 11, isCurrent ? 0x3338BDF8 : 0x221E293B);
			graphics.outline(badgeX, badgeY, badgeBoxW, 11, (badgeColor & 0x00FFFFFF) | 0x55000000);
			graphics.text(this.font, badge, badgeX + 3, badgeY + 2, badgeColor, false);

			// Title + Subtitle/Artist clipped neatly
			int textX = innerLeft + 25;
			int availTextW = Math.max(40, badgeX - textX - 6);
			String primary = track.displayTitle();
			String artist = track.artist();

			if (artist != null && !artist.isBlank()) {
				String fittedPrimary = clipText(primary, availTextW);
				graphics.text(this.font, fittedPrimary, textX, rowTop + 3, isCurrent ? 0xFFF8FAFC : 0xFFE2E8F0, isCurrent);
				String fittedArtist = clipText(artist, availTextW);
				graphics.text(this.font, fittedArtist, textX, rowTop + 12, isCurrent ? 0xFF7DD3FC : 0xFF64748B, false);
			} else {
				String fitted = clipText(primary, availTextW);
				graphics.text(this.font, fitted, textX, rowTop + 7, isCurrent ? 0xFFF8FAFC : 0xFFE2E8F0, isCurrent);
			}
		}
		graphics.disableScissor();

		// Scrollbar
		int contentHeight = filteredTracks.size() * ROW_HEIGHT;
		if (contentHeight > viewHeight) {
			int sbX1 = innerRight - 4;
			int sbX2 = innerRight - 1;
			graphics.fill(sbX1, innerTop + 1, sbX2, innerBottom - 1, 0x441E293B);
			int thumbH = Math.max(18, (int) ((viewHeight * (double) viewHeight) / contentHeight));
			double maxScroll = contentHeight - viewHeight;
			int thumbY = innerTop + 1 + (int) Math.round((scrollOffset / maxScroll) * (viewHeight - 2 - thumbH));
			graphics.fill(sbX1, thumbY, sbX2, thumbY + thumbH, draggingScrollbar ? 0xFF38BDF8 : 0xAA64748B);
		}
	}

	private void renderSpeakerList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int innerLeft = listLeft + 1;
		int innerTop = listTop + 1;
		int innerRight = listRight - 1;
		int innerBottom = listBottom - 1;
		int viewHeight = innerBottom - innerTop;

		if (currentSpeakers.isEmpty()) {
			int cy = innerTop + viewHeight / 2 - 10;
			graphics.centeredText(
					this.font,
					Component.translatable("screen.mp3player.speakers.empty.title"),
					panelX + panelW / 2,
					cy,
					0xFFE2E8F0
			);
			graphics.centeredText(
					this.font,
					Component.translatable("screen.mp3player.speakers.empty.subtitle"),
					panelX + panelW / 2,
					cy + 13,
					0xFF94A3B8
			);
			return;
		}

		boolean inListBounds = mouseX >= innerLeft && mouseX < innerRight - 5 && mouseY >= innerTop && mouseY < innerBottom;
		boolean globalStreamsOn = controller.config().linkedSpeakersEnabled();
		long animTick = System.currentTimeMillis();

		graphics.enableScissor(innerLeft, innerTop, innerRight, innerBottom);

		for (int i = 0; i < currentSpeakers.size(); i++) {
			int rowTop = (int) Math.round(innerTop + i * ROW_HEIGHT - speakerScrollOffset);
			int rowBottom = rowTop + ROW_HEIGHT;
			if (rowBottom < innerTop || rowTop > innerBottom) {
				continue;
			}

			LinkedSpeaker ls = currentSpeakers.get(i);
			BlockPos pos = ls.pos();
			boolean isFocused = focusedSpeakerPos != null && focusedSpeakerPos.equals(pos);
			boolean isStreaming = controller.isSpeakerStreaming(pos);
			boolean isHovered = inListBounds && mouseY >= rowTop && mouseY < rowBottom;

			if (isStreaming) {
				graphics.fill(innerLeft, rowTop, innerRight - 5, rowBottom - 1, 0x3A8B5CF6);
				graphics.fill(innerLeft, rowTop, innerLeft + 3, rowBottom - 1, 0xFFA78BFA);
			} else if (isFocused) {
				graphics.fill(innerLeft, rowTop, innerRight - 5, rowBottom - 1, 0x3310B981);
				graphics.fill(innerLeft, rowTop, innerLeft + 3, rowBottom - 1, 0xFF34D399);
			} else if (isHovered) {
				graphics.fill(innerLeft, rowTop, innerRight - 5, rowBottom - 1, 0x2A475569);
			} else if ((i & 1) == 1) {
				graphics.fill(innerLeft, rowTop, innerRight - 5, rowBottom - 1, 0x121E293B);
			}

			// Left indicator: Animated Equalizer Bars if streaming, or Speaker status icon
			int iconX = innerLeft + 7;
			if (isStreaming) {
				float level = Math.max(0.18f, controller.visualizerLevel() * ls.volume());
				for (int b = 0; b < 4; b++) {
					double wave = 0.55 + 0.45 * Math.sin((animTick / 95.0) + b * 1.45);
					int barH = Math.max(2, Math.min(12, (int) Math.round(12 * level * wave)));
					int bx = iconX + b * 3;
					int by = rowTop + 16 - barH;
					graphics.fill(bx, by, bx + 2, rowTop + 16, 0xFFA78BFA);
				}
			} else if (ls.enabled() && globalStreamsOn) {
				graphics.text(this.font, "🔊", iconX + 1, rowTop + 7, 0xFF34D399, false);
			} else {
				graphics.text(this.font, "🔇", iconX + 1, rowTop + 7, 0xFF64748B, false);
			}

			// Right-side interactive pills: [Vol %]  [AN/AUS]  [✕]
			int unlinkW = 16;
			int unlinkX = innerRight - 8 - unlinkW;
			int pillY = rowTop + 5;
			int pillH = 12;
			boolean hoverUnlink = isHovered && mouseX >= unlinkX && mouseX <= unlinkX + unlinkW;
			graphics.fill(unlinkX, pillY, unlinkX + unlinkW, pillY + pillH, hoverUnlink ? 0xAAEF4444 : 0x447F1D1D);
			graphics.outline(unlinkX, pillY, unlinkW, pillH, hoverUnlink ? 0xFFF87171 : 0xFF991B1B);
			graphics.centeredText(this.font, "✕", unlinkX + unlinkW / 2, pillY + 2, 0xFFFECACA);

			String stateStr = Component.translatable(
					ls.enabled() ? "screen.mp3player.speakers.on" : "screen.mp3player.speakers.off"
			).getString();
			int stateW = Math.max(28, this.font.width(stateStr) + 8);
			int stateX = unlinkX - 4 - stateW;
			boolean hoverState = isHovered && mouseX >= stateX && mouseX <= stateX + stateW;
			int stateBg = ls.enabled() ? (hoverState ? 0x6610B981 : 0x33059669) : (hoverState ? 0x55475569 : 0x331E293B);
			int stateBorder = ls.enabled() ? 0xFF34D399 : 0xFF475569;
			int stateTextCol = ls.enabled() ? 0xFFA7F3D0 : 0xFF94A3B8;
			graphics.fill(stateX, pillY, stateX + stateW, pillY + pillH, stateBg);
			graphics.outline(stateX, pillY, stateW, pillH, stateBorder);
			graphics.centeredText(this.font, stateStr, stateX + stateW / 2, pillY + 2, stateTextCol);

			int volPct = Math.round(ls.volume() * 100.0f);
			String volStr = volPct + "%";
			int volW = Math.max(30, this.font.width(volStr) + 8);
			int volX = stateX - 4 - volW;
			boolean hoverVol = isHovered && mouseX >= volX && mouseX <= volX + volW;
			graphics.fill(volX, pillY, volX + volW, pillY + pillH, hoverVol ? 0x558B5CF6 : 0x331E293B);
			graphics.outline(volX, pillY, volW, pillH, hoverVol ? 0xFFA78BFA : 0xFF475569);
			graphics.centeredText(this.font, volStr, volX + volW / 2, pillY + 2, 0xFFDDD6FE);

			// Speaker Title & Coordinates + Distance / Status Subtitle
			int textX = innerLeft + 25;
			int availTextW = Math.max(40, volX - textX - 6);
			String speakerTitle = Component.translatable(
					"screen.mp3player.speakers.entry_title",
					i + 1,
					formatPosShort(pos)
			).getString();
			graphics.text(
					this.font,
					clipText(speakerTitle, availTextW),
					textX,
					rowTop + 3,
					ls.enabled() ? 0xFFF8FAFC : 0xFF94A3B8,
					isStreaming
			);

			double dist = 0.0;
			if (this.minecraft != null && this.minecraft.player != null) {
				dist = Math.sqrt(pos.distToCenterSqr(this.minecraft.player.position()));
			}
			String distStr = String.format(Locale.ROOT, "%.1fm", dist);
			String statusKey;
			if (!ls.enabled() || !globalStreamsOn) {
				statusKey = "screen.mp3player.speakers.status.muted";
			} else if (isStreaming) {
				statusKey = dist <= 54.0
						? "screen.mp3player.speakers.status.streaming"
						: "screen.mp3player.speakers.status.far";
			} else {
				statusKey = "screen.mp3player.speakers.status.ready";
			}
			String subText = distStr + " • " + Component.translatable(statusKey).getString();
			int subColor = isStreaming ? 0xFFC4B5FD : 0xFF64748B;
			graphics.text(this.font, clipText(subText, availTextW), textX, rowTop + 12, subColor, false);
		}

		graphics.disableScissor();

		// Scrollbar for speaker list
		int contentHeight = currentSpeakers.size() * ROW_HEIGHT;
		if (contentHeight > viewHeight) {
			int sbX1 = innerRight - 4;
			int sbX2 = innerRight - 1;
			graphics.fill(sbX1, innerTop + 1, sbX2, innerBottom - 1, 0x441E293B);
			int thumbH = Math.max(18, (int) ((viewHeight * (double) viewHeight) / contentHeight));
			double maxScroll = contentHeight - viewHeight;
			int thumbY = innerTop + 1 + (int) Math.round((speakerScrollOffset / maxScroll) * (viewHeight - 2 - thumbH));
			graphics.fill(sbX1, thumbY, sbX2, thumbY + thumbH, draggingScrollbar ? 0xFFA78BFA : 0xAA64748B);
		}
	}

	private void renderNowPlayingAndSeekBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Mp3TrackInfo current = controller.currentTrack();

		// Status badge on right (Boombox status, Linked Speakers count, remote 3D streams count, or local fallback info)
		String rightStatus = null;
		int rightColor = 0xFF64748B;
		int remoteCount = remoteSpeakers.activeCount();
		int activeLinked = controller.activeLinkedSpeakerCount();

		if (controller.isBoomboxActive() && activeLinked > 0) {
			rightStatus = "📻+" + activeLinked + "🔊 3D";
			rightColor = 0xFF34D399;
		} else if (controller.isBoomboxActive()) {
			rightStatus = "📻 3D";
			rightColor = 0xFF34D399;
		} else if (activeLinked > 0 && (controller.isSpeakerSystemMode() || controller.hasActiveTrack())) {
			rightStatus = "🔊 " + activeLinked + " Streams";
			rightColor = 0xFFA78BFA;
		} else if (remoteCount > 0) {
			rightStatus = "🔊 " + remoteCount + " 3D";
			rightColor = controller.config().muteOthers() ? 0xFFF87171 : 0xFF34D399;
		} else if ((controller.config().speaker3dMode() || isBoomboxScreen() || speakerControllerScreen)
				&& !controller.isServerSupporting3D()) {
			rightStatus = Component.translatable("screen.mp3player.server_local_only").getString();
			rightColor = 0xFFFBBF24;
		}

		int rightStatusW = 0;
		if (rightStatus != null) {
			rightStatusW = this.font.width(rightStatus) + 8;
			graphics.text(this.font, rightStatus, panelX + panelW - 8 - this.font.width(rightStatus), deckTop + 5, rightColor, false);
		}

		// Now-Playing Song Label on left (or show another player's track if they are streaming on this Boombox/Speaker!)
		int maxNowPlayingW = panelW - 16 - rightStatusW;
		RemoteStream remoteOnBox = isBoomboxScreen()
				? remoteSpeakers.findStreamAtBoombox(targetBoomboxPos)
				: (focusedSpeakerPos != null ? remoteSpeakers.findStreamAtSpeaker(focusedSpeakerPos) : null);
		if (remoteOnBox != null && !controller.isTargetingBoombox(targetBoomboxDimension, targetBoomboxPos)) {
			String remotePrefix = remoteOnBox.paused() ? "⏸ 📻 " : "♪ 📻 ";
			String label = clipText(remotePrefix + remoteOnBox.speakerName() + " – " + remoteOnBox.trackTitle(), maxNowPlayingW);
			graphics.text(this.font, label, panelX + 8, deckTop + 5, 0xFF34D399, true);
		} else if (current != null) {
			String prefix = controller.isPlaying() ? "♪ " : "⏸ ";
			String label = clipText(prefix + current.fullDisplayLabel(), maxNowPlayingW);
			graphics.text(this.font, label, panelX + 8, deckTop + 5, 0xFF7DD3FC, true);
		} else {
			String idle = clipText(Component.translatable("screen.mp3player.nothing_playing").getString(), maxNowPlayingW);
			graphics.text(this.font, idle, panelX + 8, deckTop + 5, 0xFF64748B, false);
		}

		// Timestamps + Seek Bar
		double ratio = draggingSeek ? dragSeekRatio : controller.progressRatio();
		int totalSec = current != null ? current.durationSeconds() : 0;
		int posSec = draggingSeek && totalSec > 0
				? (int) Math.round(ratio * totalSec)
				: controller.currentPositionSeconds();

		String leftTime = Mp3TrackInfo.formatSeconds(posSec);
		String rightTime = Mp3TrackInfo.formatSeconds(totalSec);
		graphics.text(this.font, leftTime, panelX + 8, seekBarY - 1, 0xFF94A3B8, false);
		graphics.text(this.font, rightTime, panelX + panelW - 8 - this.font.width(rightTime), seekBarY - 1, 0xFF94A3B8, false);

		int barH = 6;
		int barWidth = Math.max(20, seekRight - seekLeft);
		graphics.fill(seekLeft, seekBarY, seekRight, seekBarY + barH, 0xFF1E293B);
		graphics.outline(seekLeft, seekBarY, barWidth, barH, 0xFF334155);

		int fillW = (int) Math.round(barWidth * Math.max(0.0, Math.min(1.0, ratio)));
		if (fillW > 0) {
			graphics.fill(seekLeft + 1, seekBarY + 1, Math.min(seekRight - 1, seekLeft + fillW), seekBarY + barH - 1, 0xFF0EA5E9);
			graphics.horizontalLine(seekLeft + 1, Math.min(seekRight - 1, seekLeft + fillW), seekBarY + 1, 0xFF7DD3FC);
		}

		boolean hoverSeek = current != null && mouseX >= seekLeft - 3 && mouseX <= seekRight + 3
				&& mouseY >= seekBarY - 3 && mouseY <= seekBarY + barH + 3;
		if (current != null && (hoverSeek || draggingSeek)) {
			int knobX = seekLeft + fillW;
			graphics.fill(knobX - 2, seekBarY - 2, knobX + 3, seekBarY + barH + 2, 0xFFF8FAFC);
			graphics.outline(knobX - 2, seekBarY - 2, 5, barH + 4, 0xFF0284C7);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != 0) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

		// 1. Seek bar click / drag start
		if (controller.currentTrack() != null
				&& mx >= seekLeft - 3 && mx <= seekRight + 3
				&& my >= seekBarY - 4 && my <= seekBarY + 10) {
			this.draggingSeek = true;
			this.dragSeekRatio = computeSeekRatio(mx);
			return true;
		}

		int innerLeft = listLeft + 1;
		int innerTop = listTop + 1;
		int innerRight = listRight - 1;
		int innerBottom = listBottom - 1;
		int viewHeight = innerBottom - innerTop;
		int itemCount = (viewTab == ViewTab.SPEAKERS) ? currentSpeakers.size() : filteredTracks.size();
		int contentHeight = itemCount * ROW_HEIGHT;

		// 2. Scrollbar drag start
		if (contentHeight > viewHeight && mx >= listRight - 6 && mx <= listRight && my >= innerTop && my <= innerBottom) {
			this.draggingScrollbar = true;
			updateScrollFromMouse(my);
			return true;
		}

		// 3. List Row Click (Speakers tab or Tracks tab)
		if (mx >= innerLeft && mx < innerRight - 5 && my >= innerTop && my < innerBottom) {
			if (viewTab == ViewTab.SPEAKERS) {
				int idx = (int) Math.floor((my - innerTop + speakerScrollOffset) / ROW_HEIGHT);
				if (idx >= 0 && idx < currentSpeakers.size()) {
					LinkedSpeaker clicked = currentSpeakers.get(idx);
					int unlinkW = 16;
					int unlinkX = innerRight - 8 - unlinkW;

					String stateStr = Component.translatable(
							clicked.enabled() ? "screen.mp3player.speakers.on" : "screen.mp3player.speakers.off"
					).getString();
					int stateW = Math.max(28, this.font.width(stateStr) + 8);
					int stateX = unlinkX - 4 - stateW;

					int volPct = Math.round(clicked.volume() * 100.0f);
					String volStr = volPct + "%";
					int volW = Math.max(30, this.font.width(volStr) + 8);
					int volX = stateX - 4 - volW;

					if (mx >= unlinkX && mx <= unlinkX + unlinkW) {
						controller.unlinkSpeaker(clicked.dimension(), clicked.pos());
					} else if (mx >= volX && mx <= volX + volW) {
						controller.cycleSpeakerVolume(clicked.dimension(), clicked.pos());
					} else {
						controller.setSpeakerEnabled(clicked.dimension(), clicked.pos(), !clicked.enabled());
					}
					updateSpeakerList();
					refreshButtonLabels();
					return true;
				}
			} else {
				int idx = (int) Math.floor((my - innerTop + scrollOffset) / ROW_HEIGHT);
				if (idx >= 0 && idx < filteredTracks.size()) {
					Mp3TrackInfo clicked = filteredTracks.get(idx);
					if (isBoomboxScreen()) {
						controller.playTrackOnBoombox(clicked, targetBoomboxDimension, targetBoomboxPos);
					} else if (speakerControllerScreen && !controller.isBoomboxActive()) {
						controller.playTrackOnSpeakers(clicked);
					} else {
						controller.playTrack(clicked);
					}
					refreshButtonLabels();
					return true;
				}
			}
		}

		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.draggingSeek) {
			this.dragSeekRatio = computeSeekRatio(event.x());
			return true;
		}
		if (this.draggingScrollbar) {
			updateScrollFromMouse(event.y());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.draggingSeek && event.button() == 0) {
			this.draggingSeek = false;
			controller.seek(this.dragSeekRatio);
			return true;
		}
		if (this.draggingScrollbar && event.button() == 0) {
			this.draggingScrollbar = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= listLeft && mouseX <= listRight && mouseY >= listTop && mouseY <= listBottom) {
			if (viewTab == ViewTab.SPEAKERS) {
				this.speakerScrollOffset -= scrollY * (ROW_HEIGHT * 1.25);
				clampSpeakerScroll();
			} else {
				this.scrollOffset -= scrollY * (ROW_HEIGHT * 1.25);
				clampScroll();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (searchBox != null && searchBox.isFocused()) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				searchBox.setFocused(false);
				return true;
			}
			return super.keyPressed(event);
		}

		if (event.key() == GLFW.GLFW_KEY_SPACE) {
			handlePlayPauseAction();
			refreshButtonLabels();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_LEFT && controller.currentTrack() != null) {
			int dur = Math.max(1, controller.currentTrack().durationSeconds());
			controller.seek(Math.max(0.0, (controller.currentPositionSeconds() - 5) / (double) dur));
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_RIGHT && controller.currentTrack() != null) {
			int dur = Math.max(1, controller.currentTrack().durationSeconds());
			controller.seek(Math.min(0.995, (controller.currentPositionSeconds() + 5) / (double) dur));
			return true;
		}
		if (toggleKey != null && toggleKey.matches(event)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		int count = controller.library().importFiles(paths);
		if (count > 0) {
			updateFilteredList();
			refreshButtonLabels();
			this.toastMessage = Component.translatable("screen.mp3player.dropped_files", count).getString();
			this.toastExpireTimeMs = System.currentTimeMillis() + 3500L;
		}
	}

	@Override
	public void onClose() {
		controller.config().save();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	private double computeSeekRatio(double mouseX) {
		int w = Math.max(1, seekRight - seekLeft);
		return Math.max(0.0, Math.min(0.995, (mouseX - seekLeft) / w));
	}

	private void updateScrollFromMouse(double mouseY) {
		int innerTop = listTop + 1;
		int viewHeight = Math.max(1, (listBottom - 1) - innerTop);
		double ratio = Math.max(0.0, Math.min(1.0, (mouseY - innerTop) / viewHeight));
		if (viewTab == ViewTab.SPEAKERS) {
			int contentHeight = currentSpeakers.size() * ROW_HEIGHT;
			double maxScroll = Math.max(0.0, contentHeight - viewHeight);
			this.speakerScrollOffset = ratio * maxScroll;
			clampSpeakerScroll();
		} else {
			int contentHeight = filteredTracks.size() * ROW_HEIGHT;
			double maxScroll = Math.max(0.0, contentHeight - viewHeight);
			this.scrollOffset = ratio * maxScroll;
			clampScroll();
		}
	}

	private void clampScroll() {
		int viewHeight = Math.max(1, (listBottom - 1) - (listTop + 1));
		int contentHeight = filteredTracks.size() * ROW_HEIGHT;
		double maxScroll = Math.max(0.0, contentHeight - viewHeight);
		this.scrollOffset = Math.max(0.0, Math.min(maxScroll, this.scrollOffset));
	}

	private void clampSpeakerScroll() {
		int viewHeight = Math.max(1, (listBottom - 1) - (listTop + 1));
		int contentHeight = currentSpeakers.size() * ROW_HEIGHT;
		double maxScroll = Math.max(0.0, contentHeight - viewHeight);
		this.speakerScrollOffset = Math.max(0.0, Math.min(maxScroll, this.speakerScrollOffset));
	}

	private static int formatBadgeColor(String badge, boolean active) {
		if (active) {
			return 0xFF7DD3FC;
		}
		return switch (badge) {
			case "FLAC", "WAV" -> 0xFF34D399; // Lossless emerald accent
			case "OPUS", "OGG" -> 0xFFA78BFA; // Xiph violet accent
			case "M4A" -> 0xFFFBBF24;         // AAC amber accent
			default -> 0xFF94A3B8;            // MP3 slate accent
		};
	}

	private String clipText(String text, int maxWidth) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		int targetW = Math.max(8, maxWidth - this.font.width(ellipsis));
		return this.font.plainSubstrByWidth(text, targetW) + ellipsis;
	}

	private static final class VolumeSlider extends AbstractSliderButton {
		private final Mp3AudioController controller;

		VolumeSlider(int x, int y, int width, int height, Mp3AudioController controller) {
			super(x, y, width, height, Component.empty(), controller.config().volume());
			this.controller = controller;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int pct = (int) Math.round(this.value * 100.0);
			setMessage(Component.translatable("screen.mp3player.volume", pct));
		}

		@Override
		protected void applyValue() {
			controller.setVolume((float) this.value);
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			super.onRelease(event);
			controller.config().save();
		}
	}
}
