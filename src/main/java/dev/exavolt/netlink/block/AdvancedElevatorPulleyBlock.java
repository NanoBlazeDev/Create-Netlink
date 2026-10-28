package dev.exavolt.netlink.block;

import com.zurrtum.create.content.contraptions.elevator.ElevatorPulleyBlock;
import dev.exavolt.netlink.network.DeviceRecord;
import dev.exavolt.netlink.network.Elevators;
import dev.exavolt.netlink.network.NetlinkServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Create's Elevator Pulley with a built-in Netlink device: it announces where the cabin is and where it's heading on the
 * network and can be tuned and driven by scripts. Configure it (name, network) with the terminal HUD on.
 */
public class AdvancedElevatorPulleyBlock extends ElevatorPulleyBlock {
	public AdvancedElevatorPulleyBlock(Properties properties) {
		super(properties);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level instanceof ServerLevel sl) {
			Elevators.register(sl, pos, placer);
		}
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
	                                      BlockHitResult hitResult) {
		if (stack.isEmpty() && !player.isShiftKeyDown()) {
			if (level.isClientSide()) {
				if (NetlinkServer.clientHudOn.getAsBoolean()) {
					return InteractionResult.SUCCESS;
				}
			} else if (player instanceof ServerPlayer sp && NetlinkServer.hudOn(sp)) {
				DeviceRecord d = NetlinkServer.device(DeviceRecord.key(DeviceRecord.Kind.ELEVATOR, level.dimension(), pos));
				if (d == null) {
					Elevators.register((ServerLevel) level, pos, sp);
					d = NetlinkServer.device(DeviceRecord.key(DeviceRecord.Kind.ELEVATOR, level.dimension(), pos));
				}
				if (d != null && NetlinkServer.requireImplant(sp)) {
					NetlinkServer.openDeviceConfig(sp, d);
				}
				return InteractionResult.SUCCESS;
			}
		}
		return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
	}
}
