package dev.exavolt.netlink.block;

import com.mojang.serialization.MapCodec;
import com.zurrtum.create.AllShapes;
import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.network.NetlinkServer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Create's Redstone Link, rebuilt on the Netlink: no frequency items, unlimited range, configured from a terminal.
 * RECEIVER=true means LISTEN (outputs the channel), false means POST (feeds its input into the channel).
 */
public class AdvancedRedstoneLinkBlock extends BaseEntityBlock {
	public static final MapCodec<AdvancedRedstoneLinkBlock> CODEC = simpleCodec(AdvancedRedstoneLinkBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
	public static final BooleanProperty RECEIVER = BooleanProperty.create("receiver");

	public AdvancedRedstoneLinkBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(POWERED, false).setValue(RECEIVER, false));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, POWERED, RECEIVER);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getClickedFace());
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
		super.setPlacedBy(level, pos, state, by, itemStack);
		if (!level.isClientSide() && by instanceof ServerPlayer player && level.getBlockEntity(pos) instanceof AdvancedRedstoneLinkBlockEntity be) {
			NetlinkServer.touchNetwork(player);
			be.setOwner(player.getUUID());
		}
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new AdvancedRedstoneLinkBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return null;
		}
		return createTickerHelper(type, NetlinkRegistry.ADVANCED_REDSTONE_LINK_BE, (l, p, s, be) -> be.serverTick());
	}

	// ------------------------------------------------------------------ redstone

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
		if (level.isClientSide()) {
			return;
		}
		if (!canSurvive(state, level, pos)) {
			level.destroyBlock(pos, true);
			return;
		}
		// unlike Create's link (which waits a tick), transmit in the same tick the input changes
		updateTransmittedSignal(state, level, pos);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		updateTransmittedSignal(state, level, pos);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (state.is(oldState.getBlock()) || movedByPiston) {
			return;
		}
		if (!level.isClientSide()) {
			level.scheduleTick(pos, this, 1);
		}
	}

	public void updateTransmittedSignal(BlockState state, Level level, BlockPos pos) {
		if (level.isClientSide() || state.getValue(RECEIVER)) {
			return;
		}
		int power = inputPower(level, state, pos);
		if (state.getValue(POWERED) != power > 0) {
			level.setBlock(pos, state.setValue(POWERED, power > 0), Block.UPDATE_CLIENTS);
		}
		if (level.getBlockEntity(pos) instanceof AdvancedRedstoneLinkBlockEntity be) {
			be.transmit(power);
		}
	}

	static int inputPower(Level level, BlockState state, BlockPos pos) {
		int power = 0;
		for (Direction direction : Direction.values()) {
			power = Math.max(level.getSignal(pos.relative(direction), direction), power);
		}
		for (Direction direction : Direction.values()) {
			if (state.getValue(FACING).getOpposite() != direction) {
				power = Math.max(level.getSignal(pos.relative(direction), Direction.UP), power);
			}
		}
		return power;
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return state.getValue(RECEIVER);
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		if (!state.getValue(RECEIVER)) {
			return 0;
		}
		return level.getBlockEntity(pos) instanceof AdvancedRedstoneLinkBlockEntity be ? be.getReceived() : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		if (direction != state.getValue(FACING)) {
			return 0;
		}
		return getSignal(state, level, pos, direction);
	}

	/** Called by the block entity when the received value changes. */
	void notifyOutput(Level level, BlockPos pos, BlockState state) {
		level.updateNeighborsAt(pos, this);
		BlockPos attached = pos.relative(state.getValue(FACING).getOpposite());
		level.updateNeighborsAt(attached, this);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		NetlinkServer.removeDevice(dev.exavolt.netlink.network.DeviceRecord.key(
			dev.exavolt.netlink.network.DeviceRecord.Kind.LINK, level.dimension(), pos));
		level.updateNeighborsAt(pos.relative(state.getValue(FACING).getOpposite()), this);
	}

	// ------------------------------------------------------------------ interaction

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer sp) || !(level.getBlockEntity(pos) instanceof AdvancedRedstoneLinkBlockEntity be)) {
			return InteractionResult.PASS;
		}
		if (!NetlinkServer.requireImplant(sp)) {
			return InteractionResult.SUCCESS;
		}
		if (!NetlinkServer.canModify(sp.getUUID(), be.getOwner())) {
			sp.sendOverlayMessage(Component.translatable("message.createnetlink.not_authorized", NetlinkServer.ownerName(be.getOwner()))
				.withStyle(ChatFormatting.RED));
			return InteractionResult.SUCCESS;
		}
		if (player.isShiftKeyDown()) {
			be.configure(be.getOwner() == null ? sp.getUUID() : be.getOwner(), be.getChannel(), !state.getValue(RECEIVER));
			sp.sendOverlayMessage(Component.translatable(
				state.getValue(RECEIVER) ? "message.createnetlink.mode_post" : "message.createnetlink.mode_listen").withStyle(ChatFormatting.AQUA));
			return InteractionResult.SUCCESS;
		}
		NetlinkServer.openLinkConfig(sp, be);
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ placement & shape

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		BlockPos neighbourPos = pos.relative(state.getValue(FACING).getOpposite());
		return !level.getBlockState(neighbourPos).canBeReplaced();
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return AllShapes.REDSTONE_BRIDGE.get(state.getValue(FACING));
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType type) {
		return false;
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
