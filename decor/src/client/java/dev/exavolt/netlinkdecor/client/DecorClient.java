package dev.exavolt.netlinkdecor.client;

import dev.exavolt.netlinkdecor.DecorRegistry;
import dev.exavolt.netlinkdecor.client.render.PropRenderer;
import dev.exavolt.netlinkdecor.client.render.VoxelBlockModel;
import dev.exavolt.netlinkdecor.client.screen.PropConfigScreen;
import dev.exavolt.netlinkdecor.net.DecorPackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class DecorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PropRenderer.init();
		BlockEntityRenderers.register(DecorRegistry.LIVE_BE, ctx -> new PropRenderer());
		com.zurrtum.create.client.AllModels.register(DecorRegistry.VOXEL_BLOCK, VoxelBlockModel::new);
		ClientPlayNetworking.registerGlobalReceiver(DecorPackets.OpenPropConfig.TYPE, (p, ctx) -> ctx.client().setScreen(new PropConfigScreen(p)));
		ClientTickEvents.START_CLIENT_TICK.register(ChiselClient::startTick);
		ClientTickEvents.END_CLIENT_TICK.register(ChiselClient::endTick);
	}
}
