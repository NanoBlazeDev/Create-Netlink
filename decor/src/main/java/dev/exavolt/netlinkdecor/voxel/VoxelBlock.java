package dev.exavolt.netlinkdecor.voxel;

import com.mojang.serialization.MapCodec;
import dev.exavolt.netlinkdecor.DecorRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A sculpted block made of voxels. The voxels are stored facing north; FACING and MIRRORED say how the block is turned,
 * so WorldEdit's //rotate, structure blocks and Create contraptions turn the sculpture along with the block.
 */
public class VoxelBlock extends HorizontalDirectionalBlock implements EntityBlock {
	public static final MapCodec<VoxelBlock> CODEC = simpleCodec(VoxelBlock::new);
	public static final BooleanProperty MIRRORED = BooleanProperty.create("mirrored");
	public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);

	public VoxelBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MIRRORED, false).setValue(LIGHT, 0));
	}

	/** Quarter turns clockwise from north. */
	public static int steps(BlockState state) {
		return switch (state.getValue(FACING)) {
			case EAST -> 1;
			case SOUTH -> 2;
			case WEST -> 3;
			default -> 0;
		};
	}

	public static Direction facingOf(int steps) {
		return switch (Math.floorMod(steps, 4)) {
			case 1 -> Direction.EAST;
			case 2 -> Direction.SOUTH;
			case 3 -> Direction.WEST;
			default -> Direction.NORTH;
		};
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, MIRRORED, LIGHT);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return level.getBlockEntity(pos) instanceof VoxelBlockEntity be ? be.shape(state) : Shapes.block();
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return true;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new VoxelBlockEntity(pos, state);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
		super.setPlacedBy(level, pos, state, by, stack);
		VoxelData data = stack.get(DecorRegistry.VOXEL_DATA);
		if (level.isClientSide()) {
			return;
		}
		if (data == null || data.isEmpty()) {
			// an empty sculpture would be an invisible full block: don't leave one behind
			level.removeBlock(pos, false);
		} else if (level.getBlockEntity(pos) instanceof VoxelBlockEntity be) {
			be.setData(data.copy());
		}
	}

	public static ItemStack itemFor(VoxelData data) {
		ItemStack stack = new ItemStack(DecorRegistry.VOXEL_MODEL);
		stack.set(DecorRegistry.VOXEL_DATA, data);
		return stack;
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide() && !player.isCreative() && level.getBlockEntity(pos) instanceof VoxelBlockEntity be && !be.data().isEmpty()) {
			popResource(level, pos, itemFor(be.data()));
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
		if (level.getBlockEntity(pos) instanceof VoxelBlockEntity be && !be.data().isEmpty()) {
			return itemFor(be.data());
		}
		return new ItemStack(DecorRegistry.VOXEL_MODEL);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	/** Mirroring flips the sculpture: world = R^s * M^m, so a mirror turns it into R^s' * M^(m+1). */
	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		int s = steps(state);
		boolean m = state.getValue(MIRRORED);
		return switch (mirror) {
			case FRONT_BACK -> state.setValue(FACING, facingOf(-s)).setValue(MIRRORED, !m);
			case LEFT_RIGHT -> state.setValue(FACING, facingOf(2 - s)).setValue(MIRRORED, !m);
			default -> state;
		};
	}
}
