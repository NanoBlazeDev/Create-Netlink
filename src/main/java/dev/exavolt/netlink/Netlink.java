package dev.exavolt.netlink;

import dev.exavolt.netlink.command.NetworkCommand;
import dev.exavolt.netlink.net.NetlinkPackets;
import dev.exavolt.netlink.network.NetlinkServer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Netlink implements ModInitializer {
	public static final String MOD_ID = "createnetlink";
	public static final Logger LOGGER = LoggerFactory.getLogger("Create: Netlink");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		NetlinkRegistry.register();
		NetlinkPackets.register();
		NetworkCommand.register();
		NetlinkServer.registerEvents();
		LOGGER.info("Create: Netlink online");
	}
}
