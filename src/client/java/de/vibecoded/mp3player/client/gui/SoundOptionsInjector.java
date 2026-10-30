package de.vibecoded.mp3player.client.gui;

import de.vibecoded.mp3player.client.audio.Mp3AudioController;
import de.vibecoded.mp3player.client.audio.RemoteSpeakerManager;
import de.vibecoded.mp3player.client.config.Mp3Config;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Injects a native Minecraft volume slider ("MP3 & Boombox") directly into Minecraft's
 * Music & Sounds Options screen (SoundOptionsScreen) using Fabric ScreenEvents with zero Mixins.
 */
public final class SoundOptionsInjector {
	private SoundOptionsInjector() {
	}

	public static void register(
			Mp3Config config,
			Mp3AudioController audioController,
			RemoteSpeakerManager remoteSpeakerManager
	) {
		OptionInstance<Double> mp3VolumeOption = new OptionInstance<>(
				"options.mp3player.volume",
				OptionInstance.cachedConstantTooltip(Component.translatable("options.mp3player.volume.tooltip")),
				(caption, value) -> {
					int pct = (int) Math.round(value * 100.0);
					return pct <= 0
							? Options.genericValueLabel(caption, CommonComponents.OPTION_OFF)
							: Options.genericValueLabel(caption, Component.literal(pct + "%"));
				},
				OptionInstance.UnitDouble.INSTANCE,
				(double) config.optionsVolume(),
				newValue -> {
					config.setOptionsVolume(newValue.floatValue());
					Minecraft mc = Minecraft.getInstance();
					audioController.refreshImmediateGain(mc);
					remoteSpeakerManager.refreshImmediateGain(mc);
				}
		);

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof SoundOptionsScreen soundScreen) {
				injectIntoSoundOptions(client, soundScreen, config, mp3VolumeOption);
			}
		});
	}

	private static void injectIntoSoundOptions(
			Minecraft client,
			SoundOptionsScreen soundScreen,
			Mp3Config config,
			OptionInstance<Double> mp3VolumeOption
	) {
		OptionsList[] foundList = new OptionsList[1];
		soundScreen.layout.visitWidgets(widget -> {
			if (widget instanceof OptionsList list) {
				foundList[0] = list;
			}
		});

		OptionsList optionsList = foundList[0];
		if (optionsList == null || optionsList.findOption(mp3VolumeOption) != null) {
			return;
		}

		// Sync option slider with current persisted config value
		mp3VolumeOption.set((double) config.optionsVolume());
		optionsList.addBig(mp3VolumeOption);

		// Move the slider right below the vanilla sound category volume sliders (right above Audio Device)
		AbstractWidget deviceWidget = (client != null && client.options != null)
				? optionsList.findOption(client.options.soundDevice())
				: null;
		reorderAddedEntry(optionsList, deviceWidget);

		ScreenEvents.remove(soundScreen).register(removed -> config.save());
	}

	private static <E extends ContainerObjectSelectionList.Entry<E>> void reorderAddedEntry(
			AbstractSelectionList<E> optionsList,
			AbstractWidget deviceWidget
	) {
		List<E> entries = new ArrayList<>(optionsList.children());
		if (entries.size() >= 2) {
			E mp3Entry = entries.removeLast();
			int insertIndex = Math.min(6, entries.size());
			if (deviceWidget != null) {
				for (int i = 0; i < entries.size(); i++) {
					if (entries.get(i).children().contains(deviceWidget)) {
						insertIndex = i;
						break;
					}
				}
			}
			entries.add(insertIndex, mp3Entry);
			optionsList.replaceEntries(entries);
		}
	}
}
