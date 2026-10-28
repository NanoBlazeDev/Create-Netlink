package dev.exavolt.netlinkdecor.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/** A thin glowing light panel that sticks to any face. FACING points away from the surface it's on. */
public class NeonPanelBlock extends DirectionalBlock {
	public static final MapCodec<NeonPanelBlock> CODEC = simpleCodec(p -> new NeonPanelBlock(DyeColor.WHITE, p));
	private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

	static {
		SHAPES.put(Direction.UP, Block.box(1, 0, 1, 15, 1, 15));
		SHAPES.put(Direction.DOWN, Block.box(1, 15, 1, 15, 16, 15));
		SHAPES.put(Direction.NORTH, Block.box(1, 1, 15, 15, 15, 16));
		SHAPES.put(Direction.SOUTH, Block.box(1, 1, 0, 15, 15, 1));
		SHAPES.put(Direction.WEST, Block.box(15, 1, 1, 16, 15, 15));
		SHAPES.put(Direction.EAST, Block.box(0, 1, 1, 1, 15, 15));
	}

	public final DyeColor color;

	public NeonPanelBlock(DyeColor color, Properties properties) {
		super(properties);
		this.color = color;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
	}

	@Override
	protected MapCodec<? extends DirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getClickedFace());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}
}
