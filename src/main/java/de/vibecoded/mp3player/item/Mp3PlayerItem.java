package de.vibecoded.mp3player.item;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

public class Mp3PlayerItem extends Item {
	/**
	 * Installed by Mp3PlayerClient during client initialization.
	 * Boolean parameter indicates whether Shift (sneak) was held for quick Play/Pause.
	 */
	public static volatile Consumer<Boolean> clientUseCallback;

	public Mp3PlayerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			Consumer<Boolean> callback = clientUseCallback;
			if (callback != null) {
				callback.accept(player.isShiftKeyDown());
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(
			ItemStack stack,
			TooltipContext context,
			TooltipDisplay display,
			Consumer<Component> tooltipAdder,
			TooltipFlag flag
	) {
		tooltipAdder.accept(Component.translatable("item.mp3player.mp3_player.tooltip.open")
				.withStyle(ChatFormatting.AQUA));
		tooltipAdder.accept(Component.translatable("item.mp3player.mp3_player.tooltip.quick")
				.withStyle(ChatFormatting.GRAY));
	}
}

