package dev.exavolt.netlinkdecor.block;

import com.mojang.serialization.MapCodec;
import dev.exavolt.netlink.network.NetlinkServer;
import dev.exavolt.netlinkdecor.DecorRegistry;
import dev.exavolt.netlinkdecor.net.DecorServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * A wireless-themed prop that faces a horizontal direction (so WorldEdit's //rotate and Create's rotations turn it).
 * Live props carry a {@link LiveBlockEntity}; sneak-right-click with an empty hand links them to a channel.
 */
public class PropBlock extends HorizontalDirectionalBlock implements EntityBlock {
	public static final MapCodec<PropBlock> CODEC = simpleCodec(p -> new PropBlock(p, Shapes.block(), false, false));

	private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
	private final boolean live;
	/** Wall props: placed against the clicked face, their back on the wall. */
	private final boolean wall;

	/** @param north the shape when facing north (front towards -Z), in block pixels */
	public PropBlock(Properties properties, VoxelShape north, boolean live, boolean wall) {
		super(properties);
		this.live = live;
		this.wall = wall;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			shapes.put(d, rotate(north, d));
		}
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	/** Turns a north-facing shape to face {@code to}. */
	public static VoxelShape rotate(VoxelShape north, Direction to) {
		int steps = switch (to) {
			case EAST -> 1;
			case SOUTH -> 2;
			case WEST -> 3;
			default -> 0;
		};
		VoxelShape[] out = {Shapes.empty()};
		north.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
			double ax = x0, az = z0, bx = x1, bz = z1;
			for (int i = 0; i < steps; i++) {
				// clockwise seen from above: (x, z) -> (1 - z, x)
				double nax = 1 - bz, naz = ax, nbx = 1 - az, nbz = bx;
				ax = nax;
				az = naz;
				bx = nbx;
				bz = nbz;
			}
			out[0] = Shapes.or(out[0], Shapes.box(Math.min(ax, bx), y0, Math.min(az, bz), Math.max(ax, bx), y1, Math.max(az, bz)));
		});
		return out[0].optimize();
	}

	public boolean isLive() {
		return live;
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
		Direction face = ctx.getClickedFace();
		Direction facing = wall && face.getAxis().isHorizontal() ? face : ctx.getHorizontalDirection().getOpposite();
		return defaultBlockState().setValue(FACING, facing);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapes.get(state.getValue(FACING));
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!live || !player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide() && player instanceof ServerPlayer sp && NetlinkServer.requireImplant(sp)
			&& level.getBlockEntity(pos) instanceof LiveBlockEntity be) {
			DecorServer.openConfig(sp, be);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return live ? new LiveBlockEntity(pos, state) : null;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (!live || level.isClientSide() || type != DecorRegistry.LIVE_BE) {
			return null;
		}
		return (l, p, s, be) -> ((LiveBlockEntity) be).serverTick();
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
