package dev.exavolt.netlink.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.exavolt.netlink.Netlink;
import dev.exavolt.netlink.client.render.HudOverlay;
import dev.exavolt.netlink.client.render.XrayRenderer;
import dev.exavolt.netlink.client.screen.KeyboardConfigScreen;
import dev.exavolt.netlink.client.render.KeyboardActiveProperty;
import dev.exavolt.netlink.client.render.KeyboardModel;
import net.minecraft.client.renderer.item.ItemModels;
import dev.exavolt.netlink.client.screen.DeviceConfigScreen;
import dev.exavolt.netlink.client.screen.NetworkMapScreen;
import dev.exavolt.netlink.item.KeyboardItem;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperties;
import dev.exavolt.netlink.net.NetlinkPackets;
import dev.exavolt.netlink.network.NetlinkServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

public class NetlinkClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Netlink.id("netlink"));
	public static KeyMapping HUD_KEY;
	public static KeyMapping MAP_KEY;

	@Override
	public void onInitializeClient() {
		HUD_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.createnetlink.hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));
		MAP_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.createnetlink.map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY));

		NetlinkServer.clientHudOn = () -> ClientState.hudOn;
		NetlinkServer.clientBugAt = ClientState::bugAt;
		KeyboardItem.clientToggleActive = KeyboardClient::toggle;
		KeyboardItem.clientToggleBindMode = KeyboardClient::toggleBindMode;
		KeyboardItem.keyName = key -> InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName();
		ConditionalItemModelProperties.ID_MAPPER.put(Netlink.id("keyboard_active"), KeyboardActiveProperty.MAP_CODEC);
		ItemModels.ID_MAPPER.put(KeyboardModel.ID, KeyboardModel.Unbaked.CODEC);

		ClientPlayNetworking.registerGlobalReceiver(NetlinkPackets.HudSync.TYPE, (p, ctx) -> ClientState.setDevices(p.devices()));
		ClientPlayNetworking.registerGlobalReceiver(NetlinkPackets.OpenDeviceConfig.TYPE,
			(p, ctx) -> ctx.client().setScreen(new DeviceConfigScreen(p)));
		ClientPlayNetworking.registerGlobalReceiver(NetlinkPackets.OpenExtenderAssembly.TYPE,
			(p, ctx) -> ctx.client().setScreen(new dev.exavolt.netlink.client.screen.ExtenderAssemblyScreen(p)));
		ClientPlayNetworking.registerGlobalReceiver(NetlinkPackets.ExtenderMotion.TYPE, (p, ctx) -> {
			if (ctx.client().level != null
				&& ctx.client().level.getBlockEntity(p.master()) instanceof dev.exavolt.netlink.block.WallExtenderBlockEntity be) {
				be.clientMotion(p.extension(), p.dir(), p.tick(), p.speed());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(NetlinkPackets.ScriptState.TYPE,
			(p, ctx) -> dev.exavolt.netlink.client.screen.ScriptScreen.receive(p));
		ClientPlayNetworking.registerGlobalReceiver(NetlinkPackets.OpenKeyboardConfig.TYPE,
			(p, ctx) -> ctx.client().setScreen(new KeyboardConfigScreen(p)));
		ClientPlayNetworking.registerGlobalReceiver(NetlinkPackets.NetworkMap.TYPE, (p, ctx) -> {
			Minecraft mc = ctx.client();
			if (mc.screen instanceof NetworkMapScreen screen) {
				screen.update(p.networks());
			} else if (p.open()) {
				mc.setScreen(new NetworkMapScreen(p.networks()));
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			ClientState.reset();
			KeyboardClient.reset();
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ClientState.reset());

		ClientTickEvents.START_CLIENT_TICK.register(KeyboardClient::tick);
		ClientTickEvents.END_CLIENT_TICK.register(GlueClient::tick);
		ClientTickEvents.START_CLIENT_TICK.register(GlueClient::startTick);
		GlueClient.init();
		ElevatorPanelClient.init();
		ClientTickEvents.END_CLIENT_TICK.register(ElevatorPanelClient::tick);
		dev.exavolt.netlink.client.render.ElevatorPanelRenderer.init();
		net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(dev.exavolt.netlink.NetlinkRegistry.ELEVATOR_PANEL_BE,
			ctx -> new dev.exavolt.netlink.client.render.ElevatorPanelRenderer());
		ClientTickEvents.END_CLIENT_TICK.register(NetlinkClient::tick);

		XrayRenderer.init();
		com.zurrtum.create.client.AllModels.register(dev.exavolt.netlink.NetlinkRegistry.WALL_EXTENDER,
			dev.exavolt.netlink.client.render.WallExtenderPlateModel::new);
		net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(dev.exavolt.netlink.NetlinkRegistry.WALL_EXTENDER_BE,
			ctx -> new dev.exavolt.netlink.client.render.WallExtenderRenderer());
		net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(
			ctx -> new dev.exavolt.netlink.client.render.ExtenderPreviewRenderer(ctx.bufferSource()));
		LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(XrayRenderer::render);
		HudElementRegistry.addLast(Netlink.id("hud"), HudOverlay::extract);
		HudElementRegistry.addLast(Netlink.id("controller_bind"), (g, delta) -> KeyboardClient.renderOverlay(Minecraft.getInstance(), g));
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		while (HUD_KEY.consumeClick()) {
			toggleHud(mc);
		}
		while (MAP_KEY.consumeClick()) {
			if (!ClientState.implanted()) {
				mc.player.sendOverlayMessage(Component.translatable("message.createnetlink.no_terminal").withStyle(ChatFormatting.RED));
			} else if (mc.screen == null) {
				mc.player.connection.sendCommand("network");
			}
		}
		if (ClientState.hudOn && !ClientState.implanted()) {
			ClientState.reset();
		}
	}

	private static void toggleHud(Minecraft mc) {
		if (!ClientState.implanted()) {
			mc.player.sendOverlayMessage(Component.translatable("message.createnetlink.no_terminal").withStyle(ChatFormatting.RED));
			return;
		}
		ClientState.hudOn = !ClientState.hudOn;
		ClientState.hudSince = System.currentTimeMillis();
		if (!ClientState.hudOn) {
			ClientState.setDevices(java.util.List.of());
		}
		mc.player.playSound(ClientState.hudOn ? SoundEvents.BEACON_ACTIVATE : SoundEvents.BEACON_DEACTIVATE, 0.35F, 1.9F);
		ClientPlayNetworking.send(new NetlinkPackets.HudToggle(ClientState.hudOn));
	}
}
