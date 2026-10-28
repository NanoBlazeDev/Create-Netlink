package dev.exavolt.netlink.net;

import dev.exavolt.netlink.network.NetlinkGlue;
import dev.exavolt.netlink.network.NetlinkServer;
import dev.exavolt.netlink.network.WallExtenders;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

final class NetlinkServerHandlers {
	private NetlinkServerHandlers() {
	}

	static void register() {
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.HudToggle.TYPE,
			(p, ctx) -> NetlinkServer.setHud(ctx.player(), p.on()));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.ApplyDeviceConfig.TYPE,
			(p, ctx) -> NetlinkServer.applyDeviceConfig(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.DetachBug.TYPE,
			(p, ctx) -> NetlinkServer.detachBug(ctx.player(), p.pos()));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.ApplyKeyboardConfig.TYPE,
			(p, ctx) -> NetlinkServer.applyKeyboardConfig(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.ControllerInput.TYPE,
			(p, ctx) -> NetlinkServer.controllerInput(ctx.player(), p.keys(), p.press()));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.ControllerBind.TYPE,
			(p, ctx) -> NetlinkServer.bindController(ctx.player(), p.key(), p.linkPos()));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.RequestMap.TYPE,
			(p, ctx) -> NetlinkServer.sendMap(ctx.player(), false));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.SetChannel.TYPE,
			(p, ctx) -> NetlinkServer.setChannel(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.GlueApply.TYPE,
			(p, ctx) -> NetlinkGlue.apply(ctx.player(), p.a(), p.b()));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.GlueAuto.TYPE,
			(p, ctx) -> NetlinkGlue.auto(ctx.player(), p.pos()));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.GlueRemove.TYPE,
			(p, ctx) -> NetlinkGlue.remove(ctx.player(), p.entityId()));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.AssembleExtender.TYPE,
			(p, ctx) -> WallExtenders.assemble(ctx.player(), p.clicked(), p.side()));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.ScriptRequest.TYPE,
			(p, ctx) -> dev.exavolt.netlink.network.ScriptServer.request(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.ScriptAction.TYPE,
			(p, ctx) -> dev.exavolt.netlink.network.ScriptServer.action(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.ElevatorPanelSelect.TYPE,
			(p, ctx) -> dev.exavolt.netlink.network.Elevators.panelRequest(ctx.player(), p.entityId(), p.floor()));
		ServerPlayNetworking.registerGlobalReceiver(NetlinkPackets.RemoveExtender.TYPE,
			(p, ctx) -> WallExtenders.remove(ctx.player(), p.pos()));
	}
}
