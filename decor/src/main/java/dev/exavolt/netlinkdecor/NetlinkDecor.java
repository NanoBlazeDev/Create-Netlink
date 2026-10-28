package dev.exavolt.netlinkdecor;

import dev.exavolt.netlinkdecor.net.DecorPackets;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetlinkDecor implements ModInitializer {
	public static final String MOD_ID = "netlinkdecor";
	public static final Logger LOGGER = LoggerFactory.getLogger("Netlink: Decor");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		DecorRegistry.register();
		DecorPackets.register();
		LOGGER.info("Netlink: Decor online");
	}
}
