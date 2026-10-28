package dev.exavolt.netlink.mixin;

import com.zurrtum.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.block.ElevatorTuning;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Advanced Elevator Pulleys can be tuned by scripts: a custom top speed, acceleration and a speed multiplier replace
 * Create's values in the same motion profile (accelerate, cruise, decelerate to stop exactly at the floor). A multiplier
 * of 0 brakes smoothly and holds the cabin wherever it is. Runs on both sides with the synced tuning attachment; the
 * client also eases out any drift against the server's position.
 */
@Mixin(value = ElevatorPulleyBlockEntity.class, remap = false)
public abstract class ElevatorPulleyMixin {
	@Shadow
	private float prevSpeed;
	@Shadow
	private boolean arrived;
	@Shadow
	private int clientOffsetTarget;
	@Shadow
	private boolean initialOffsetReceived;

	@Unique
	private float netlink$drift;
	@Unique
	private boolean netlink$wasInitial;
	@Unique
	private boolean netlink$halted;

	@Shadow
	private int getTargetOffset() {
		throw new AssertionError();
	}

	@Inject(method = "getMovementSpeed", at = @At("HEAD"), cancellable = true)
	private void netlink$tunedSpeed(CallbackInfoReturnable<Float> cir) {
		ElevatorPulleyBlockEntity self = (ElevatorPulleyBlockEntity) (Object) this;
		ElevatorTuning tuning = self.getAttached(NetlinkRegistry.ELEVATOR_TUNING);
		if (tuning == null || !tuning.active() || self.getLevel() == null) {
			netlink$drift = 0;
			return;
		}
		boolean client = self.getLevel().isClientSide();
		int currentTarget = getTargetOffset();
		if (!client && currentTarget != clientOffsetTarget) {
			clientOffsetTarget = currentTarget;
			self.sendData();
		}
		float diff = currentTarget - self.offset;
		float base = Mth.clamp(KineticBlockEntity.convertToLinear(self.getSpeed() * 2), -1.99f, 1.99f);
		float top = base == 0 ? 0 : tuning.maxSpeed() > 0 ? tuning.maxSpeed() : Math.abs(base);
		float limit = Math.min(3, top * Math.max(0, tuning.speed()));
		float acc = tuning.acceleration() > 0 ? tuning.acceleration() : Mth.lerp(Math.min(1, top), 0.0075f, 0.0175f);
		float decel = (float) Math.sqrt(2 * Math.abs(diff) * acc);

		float speed = Mth.clamp(diff, -limit, limit);
		speed = Mth.clamp(speed, prevSpeed - acc, prevSpeed + acc);
		speed = Mth.clamp(speed, -decel, decel);
		arrived = Math.abs(diff) < 0.5f;
		if (Math.abs(speed) > 1 / 1024f && !client) {
			self.setChanged();
		}
		prevSpeed = speed;

		if (!client) {
			// stopped between floors: tell clients exactly where, so everyone's cabin stands at the same height
			boolean halted = !arrived && Math.abs(speed) < 1e-4f;
			if (halted && !netlink$halted) {
				self.sendData();
			}
			netlink$halted = halted;
		} else if (netlink$drift != 0) {
			float c = Math.abs(netlink$drift) < 1 / 256f ? netlink$drift : Mth.clamp(netlink$drift * 0.25f, -0.25f, 0.25f);
			netlink$drift -= c;
			speed += c;
		}
		cir.setReturnValue(speed);
	}

	@Inject(method = "read", at = @At("HEAD"))
	private void netlink$beforeRead(ValueInput view, boolean clientPacket, CallbackInfo ci) {
		netlink$wasInitial = initialOffsetReceived;
	}

	@Inject(method = "read", at = @At("RETURN"))
	private void netlink$afterRead(ValueInput view, boolean clientPacket, CallbackInfo ci) {
		ElevatorPulleyBlockEntity self = (ElevatorPulleyBlockEntity) (Object) this;
		if (!clientPacket || !netlink$wasInitial) {
			return;
		}
		LinearActuatorAccessor acc = (LinearActuatorAccessor) self;
		ElevatorTuning tuning = self.getAttached(NetlinkRegistry.ELEVATOR_TUNING);
		if (tuning != null && tuning.active()) {
			// the server's position differs from ours by this much: ease it out over the next ticks
			netlink$drift = Mth.clamp(acc.netlink$getClientOffsetDiff(), -4, 4);
			acc.netlink$setClientOffsetDiff(0);
		}
	}
}
