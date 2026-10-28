package dev.exavolt.netlink.client.render;

import dev.exavolt.netlink.client.ClientState;
import dev.exavolt.netlink.client.screen.NetlinkScreen;
import dev.exavolt.netlink.net.NetlinkPackets.HudDevice;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Heads-up layer: corner brackets, device readout under the crosshair and the controller key strip. */
public final class HudOverlay {
	private HudOverlay() {
	}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();

		if (ClientState.hudOn) {
			float t = Math.min(1f, (System.currentTimeMillis() - ClientState.hudSince) / 350f);
			int a = (int) (170 * t);
			int col = (a << 24) | 0x22B6DC;
			int len = (int) (6 + 12 * t);
			int m = 6;
			bracket(g, m, m, len, 1, 1, col);
			bracket(g, w - m - 1, m, len, -1, 1, col);
			bracket(g, m, h - m - 1, len, 1, -1, col);
			bracket(g, w - m - 1, h - m - 1, len, -1, -1, col);

			g.text(font, Component.translatable("hud.createnetlink.title"), m + 6, m + 4, (a << 24) | 0x6FE6FF, true);
			g.text(font, Component.translatable("hud.createnetlink.devices", ClientState.devices.size()), m + 6, m + 14,
				((int) (a * 0.8f) << 24) | 0x8FA6BF, true);

			HitResult hit = mc.hitResult;
			if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
				HudDevice d = ClientState.deviceAt(((BlockHitResult) hit).getBlockPos());
				if (d != null) {
					readout(g, font, d, w / 2 + 10, h / 2 + 10);
				}
			}
		}

	}

	private static void bracket(GuiGraphicsExtractor g, int x, int y, int len, int dx, int dy, int col) {
		int x2 = x + dx * len;
		int y2 = y + dy * len;
		g.fill(Math.min(x, x2), y, Math.max(x, x2) + 1, y + 1, col);
		g.fill(x, Math.min(y, y2), x + 1, Math.max(y, y2) + 1, col);
	}

	private static void readout(GuiGraphicsExtractor g, Font font, HudDevice d, int x, int y) {
		int color = 0xFF000000 | XrayRenderer.colorOf(d);
		Component title = Component.translatable(d.kind() == 1 ? "hud.createnetlink.bug" : d.kind() == 2 ? "hud.createnetlink.extender" : d.kind() == 3 ? "hud.createnetlink.elevator" : "hud.createnetlink.link");
		Component channel = d.channel().isEmpty()
			? Component.translatable("hud.createnetlink.no_channel")
			: Component.literal(d.channel());
		Component mode = Component.translatable(d.listen() ? "gui.createnetlink.listen" : "gui.createnetlink.post");
		Component owner = Component.translatable(d.foreign() ? "hud.createnetlink.foreign" : "hud.createnetlink.owner", d.ownerName());

		int width = Math.max(110, Math.max(font.width(channel), Math.max(font.width(owner), font.width(title) + 40)) + 12);
		int height = 50;
		NetlinkScreen.panel(g, x, y, width, height);
		g.fill(x + 3, y + 3, x + 5, y + height - 3, color);
		g.text(font, title, x + 9, y + 6, color, false);
		g.text(font, mode, x + width - 6 - font.width(mode), y + 6, 0xFFB8C7D9, false);
		g.text(font, channel, x + 9, y + 17, d.channel().isEmpty() ? 0xFF7A8BA3 : 0xFFE6F6FF, false);
		NetlinkScreen.signalBar(g, x + 9, y + 29, width - 30, 5, d.value(), color);
		g.text(font, String.valueOf(d.value()), x + width - 16, y + 27, 0xFFE6F6FF, false);
		g.text(font, owner, x + 9, y + 38, d.foreign() ? 0xFFFF8080 : 0xFF8FA6BF, false);
	}
}
