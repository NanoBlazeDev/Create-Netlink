package dev.exavolt.netlink.mixin;

import dev.exavolt.netlink.network.NetlinkServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Lets LISTEN bugs feed redstone straight into the block they sit on. {@code getSignal(neighbour, dir)} is how a
 * block at {@code neighbour.relative(dir.getOpposite())} samples its surroundings, so adding the bug's power there
 * powers exactly the bugged block — dust, repeaters, lamps, doors, pistons and Create machines alike.
 */
@Mixin(Level.class)
public abstract class LevelSignalMixin implements SignalGetter {
	@Shadow
	public abstract boolean isClientSide();

	@Override
	public int getSignal(final BlockPos pos, final Direction direction) {
		BlockState state = this.getBlockState(pos);
		int signal = state.getSignal(this, pos, direction);
		if (state.isRedstoneConductor(this, pos)) {
			signal = Math.max(signal, this.getDirectSignalTo(pos));
		}
		if (signal < 15 && !this.isClientSide()) {
			int bug = NetlinkServer.bugPowerAt((Level) (Object) this, pos.relative(direction.getOpposite()));
			if (bug > signal) {
				return bug;
			}
		}
		return signal;
	}
}
