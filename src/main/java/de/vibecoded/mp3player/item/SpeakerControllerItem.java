package de.vibecoded.mp3player.item;

import de.vibecoded.mp3player.Mp3PlayerMod;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Handheld remote / management item to link placed Speaker blocks and manage
 * multi-speaker 3D playback across large areas.
 */
public class SpeakerControllerItem extends Item {
	@FunctionalInterface
	public interface BlockUseCallback {
		void onUseBlock(Level level, BlockPos pos, BlockState state, boolean shiftHeld);
	}

	/**
	 * Installed by Mp3PlayerClient during client initialization.
	 */
	public static volatile Consumer<Boolean> clientUseCallback;
	public static volatile BlockUseCallback clientUseOnBlockCallback;
	public static volatile IntSupplier linkedSpeakerCountSupplier;

	public SpeakerControllerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BlockState state = level.getBlockState(pos);
		if (state.is(Mp3PlayerMod.SPEAKER_BLOCK) || state.is(Mp3PlayerMod.BOOMBOX_BLOCK)) {
			if (level.isClientSide()) {
				BlockUseCallback callback = clientUseOnBlockCallback;
				if (callback != null) {
					Player player = context.getPlayer();
					callback.onUseBlock(level, pos.immutable(), state, player != null && player.isShiftKeyDown());
				}
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
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
		IntSupplier countSupplier = linkedSpeakerCountSupplier;
		if (countSupplier != null) {
			int count = countSupplier.getAsInt();
			tooltipAdder.accept(Component.translatable("item.mp3player.speaker_controller.tooltip.count", count)
					.withStyle(count > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
		}
		tooltipAdder.accept(Component.translatable("item.mp3player.speaker_controller.tooltip.link")
				.withStyle(ChatFormatting.AQUA));
		tooltipAdder.accept(Component.translatable("item.mp3player.speaker_controller.tooltip.open")
				.withStyle(ChatFormatting.GOLD));
		tooltipAdder.accept(Component.translatable("item.mp3player.speaker_controller.tooltip.quick")
				.withStyle(ChatFormatting.GRAY));
	}
}
