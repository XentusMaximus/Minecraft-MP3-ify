package de.vibecoded.mp3player.block;

import com.mojang.serialization.MapCodec;
import de.vibecoded.mp3player.network.SpeakerRelayServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BoomboxBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<BoomboxBlock> CODEC = simpleCodec(BoomboxBlock::new);
	public static final BooleanProperty PLAYING = BooleanProperty.create("playing");

	private static final VoxelShape SHAPE_NORTH_SOUTH = Shapes.or(
			Block.box(1.0, 0.0, 4.0, 15.0, 9.0, 12.0),
			Block.box(3.0, 9.0, 7.0, 13.0, 12.0, 9.0)
	);
	private static final VoxelShape SHAPE_EAST_WEST = Shapes.or(
			Block.box(4.0, 0.0, 1.0, 12.0, 9.0, 15.0),
			Block.box(7.0, 9.0, 3.0, 9.0, 12.0, 13.0)
	);

	@FunctionalInterface
	public interface BoomboxClientUseCallback {
		void onUse(Level level, BlockPos pos, boolean shiftHeld);
	}

	/**
	 * Installed by Mp3PlayerClient during client initialization so common code
	 * never references client-only classes on dedicated servers.
	 */
	public static volatile BoomboxClientUseCallback clientUseCallback;

	public BoomboxBlock(Properties properties) {
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
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		Direction dir = state.getValue(FACING);
		return (dir == Direction.EAST || dir == Direction.WEST) ? SHAPE_EAST_WEST : SHAPE_NORTH_SOUTH;
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
			BoomboxClientUseCallback callback = clientUseCallback;
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
			SpeakerRelayServer.onBoomboxRemoved(level.getServer(), level, pos);
		}
	}
}

