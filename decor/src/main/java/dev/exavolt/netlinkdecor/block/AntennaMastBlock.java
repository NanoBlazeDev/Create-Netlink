package dev.exavolt.netlinkdecor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** A lattice radio mast; the top segment carries a blinking aviation light. Stack them as high as you like. */
public class AntennaMastBlock extends PropBlock {
	public static final BooleanProperty TOP = BooleanProperty.create("top");

	public AntennaMastBlock(Properties properties, VoxelShape north) {
		super(properties, north, true, false);
		registerDefaultState(defaultBlockState().setValue(TOP, true));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(TOP);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
		BlockState s = super.getStateForPlacement(ctx);
		return s == null ? null : s.setValue(TOP, !ctx.getLevel().getBlockState(ctx.getClickedPos().above()).is(this));
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
	                                 BlockPos neighborPos, BlockState neighbor, RandomSource random) {
		if (direction == Direction.UP) {
			return state.setValue(TOP, !neighbor.is(this));
		}
		return state;
	}
}
