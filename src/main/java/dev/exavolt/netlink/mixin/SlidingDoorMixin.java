package dev.exavolt.netlink.mixin;

import com.zurrtum.create.content.contraptions.behaviour.MovementContext;
import com.zurrtum.create.content.contraptions.elevator.ElevatorContraption;
import com.zurrtum.create.content.decoration.slidingDoor.SlidingDoorMovementBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create opens elevator doors whenever the cabin stands still. A cabin held in place by a script (Speed = 0) already
 * has a new target, so it is "departing": its doors close and stay closed until it arrives.
 */
@Mixin(value = SlidingDoorMovementBehaviour.class, remap = false)
public abstract class SlidingDoorMixin {
	@Inject(method = "shouldOpen", at = @At("HEAD"), cancellable = true)
	private void netlink$closedWhileDeparting(MovementContext context, CallbackInfoReturnable<Boolean> cir) {
		if (!context.disabled && context.contraption instanceof ElevatorContraption ec && !ec.arrived) {
			context.temporaryData = null;
			cir.setReturnValue(false);
		}
	}
}
