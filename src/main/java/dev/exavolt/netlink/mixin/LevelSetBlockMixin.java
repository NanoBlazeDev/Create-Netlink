package dev.exavolt.netlink.mixin;

import dev.exavolt.netlink.network.NetlinkServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets POST bugs react in the same tick their block changes (lever flipped, lamp lit, dust powered...). */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void createnetlink$onSetBlock(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			NetlinkServer.onBlockChanged((Level) (Object) this, pos);
		}
	}
}
