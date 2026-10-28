package dev.exavolt.netlink.mixin;

import com.zurrtum.create.content.contraptions.piston.LinearActuatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = LinearActuatorBlockEntity.class, remap = false)
public interface LinearActuatorAccessor {
	@Accessor("clientOffsetDiff")
	float netlink$getClientOffsetDiff();

	@Accessor("clientOffsetDiff")
	void netlink$setClientOffsetDiff(float value);
}
