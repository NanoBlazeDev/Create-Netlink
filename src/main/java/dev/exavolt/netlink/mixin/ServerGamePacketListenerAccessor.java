package dev.exavolt.netlink.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a moving Wall Extender carry players without the server mistaking the ride for flying. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {
	@Accessor("aboveGroundTickCount")
	void netlink$setAboveGroundTickCount(int ticks);
}
