package de.vibecoded.mp3player.block;

import com.mojang.serialization.MapCodec;
import de.vibecoded.mp3player.network.SpeakerRelayServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Full 16x16x16 linkable 3D Speaker block.
 * Emits zero particles during playback and streams audio from its block position
 * as an additional 3D audio source to cover larger areas.
 */
public class SpeakerBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);
	public static final BooleanProperty PLAYING = BooleanProperty.create("playing");

	@FunctionalInterface
	public interface SpeakerClientUseCallback {
		void onUse(Level level, BlockPos pos, boolean shiftHeld);
	}

	/**
	 * Installed by Mp3PlayerClient during client initialization so common code
	 * never references client-only classes on dedicated servers.
	 */
	public static volatile SpeakerClientUseCallback clientUseCallback;

	public SpeakerBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any()
				.setValue(FACING, Direction.NORTH)
				.setValue(PLAYING, false));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, PLAYING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState()
				.setValue(FACING, context.getHorizontalDirection().getOpposite())
				.setValue(PLAYING, false);
	}

	@Override
	protected InteractionResult useWithoutItem(
			BlockState state,
			Level level,
			BlockPos pos,
			Player player,
			BlockHitResult hitResult
	) {
		if (level.isClientSide()) {
			SpeakerClientUseCallback callback = clientUseCallback;
			if (callback != null) {
				callback.onUse(level, pos.immutable(), player.isShiftKeyDown());
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		if (!level.getBlockState(pos).is(this)) {
			SpeakerRelayServer.onSpeakerRemoved(level.getServer(), level, pos);
		}
	}
}
