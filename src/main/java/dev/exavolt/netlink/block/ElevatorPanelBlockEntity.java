package dev.exavolt.netlink.block;

import dev.exavolt.netlink.NetlinkRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Holds no data; it exists so the panel's keys and screen can be drawn (and animated) by a renderer, also on contraptions. */
public class ElevatorPanelBlockEntity extends BlockEntity {
	public ElevatorPanelBlockEntity(BlockPos pos, BlockState state) {
		super(NetlinkRegistry.ELEVATOR_PANEL_BE, pos, state);
	}
}
