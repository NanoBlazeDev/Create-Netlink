package dev.exavolt.netlink.block;

import com.mojang.serialization.MapCodec;
import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.network.NetlinkServer;
import dev.exavolt.netlink.network.WallExtenders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * A plate of a Wall Extender: iron with a slot in the middle that the collapsed structure slides in and out of.
 * FACING is the direction the structure extends towards. Configurable only through the terminal HUD (H).
 */
public class WallExtenderBlock extends BaseEntityBlock {
	public static final MapCodec<WallExtenderBlock> CODEC = simpleCodec(WallExtenderBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

	public WallExtenderBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new WallExtenderBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return createTickerHelper(type, NetlinkRegistry.WALL_EXTENDER_BE, (l, p, s, be) -> be.clientTick());
		}
		return createTickerHelper(type, NetlinkRegistry.WALL_EXTENDER_BE, (l, p, s, be) -> be.serverTick());
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (level.isClientSide()) {
			return NetlinkServer.clientHudOn.getAsBoolean() ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer sp && NetlinkServer.hudOn(sp)) {
			WallExtenders.openConfig(sp, pos);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	/** Plates take as long to "break" as the block they were built into (breaking one just takes the extender off). */
	@Override
	protected float getDestroyProgress(BlockState state, Player player, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof WallExtenderBlockEntity be) {
			return be.disguise().getDestroyProgress(player, level, pos);
		}
		return super.getDestroyProgress(state, player, level, pos);
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
