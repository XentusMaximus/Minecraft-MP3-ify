package de.vibecoded.mp3player.item;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

public class BoomboxItem extends BlockItem {
	public BoomboxItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(
			ItemStack stack,
			TooltipContext context,
			TooltipDisplay display,
			Consumer<Component> tooltipAdder,
			TooltipFlag flag
	) {
		tooltipAdder.accept(Component.translatable("item.mp3player.boombox.tooltip.place")
				.withStyle(ChatFormatting.AQUA));
		tooltipAdder.accept(Component.translatable("item.mp3player.boombox.tooltip.quick")
				.withStyle(ChatFormatting.GRAY));
	}
}

