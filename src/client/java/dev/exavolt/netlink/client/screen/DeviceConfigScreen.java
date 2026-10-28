package dev.exavolt.netlink.client.screen;

import dev.exavolt.netlink.net.NetlinkPackets;
import dev.exavolt.netlink.net.NetlinkPackets.NetEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Configure an Advanced Redstone Link or a Bug: network, LISTEN/POST and channel. */
public class DeviceConfigScreen extends NetlinkScreen {
	private final NetlinkPackets.OpenDeviceConfig data;
	private final List<NetEntry> networks;
	private int networkIndex;
	private boolean listen;
	private EditBox channelBox;
	private EditBox speedBox;
	private boolean invert;
	private int chipScroll;
	private final List<int[]> chipRects = new ArrayList<>();
	private final List<String> chipNames = new ArrayList<>();

	public DeviceConfigScreen(NetlinkPackets.OpenDeviceConfig data) {
		super(Component.translatable(data.kind() == 1 ? "gui.createnetlink.bug.title" : data.kind() == 2 ? "gui.createnetlink.extender.title"
			: data.kind() == 3 ? "gui.createnetlink.elevator.title" : "gui.createnetlink.link.title"),
			256, data.foreign() ? 112 : 196);
		this.data = data;
		this.networks = data.networks();
		this.listen = data.listen() && data.allowListen();
		this.networkIndex = 0;
		this.invert = data.invert();
		if (data.owner().isPresent()) {
			for (int k = 0; k < networks.size(); k++) {
				if (networks.get(k).owner().equals(data.owner().get())) {
					networkIndex = k;
				}
			}
		}
	}

	private boolean isBug() {
		return data.kind() == 1;
	}

	private boolean isExtender() {
		return data.kind() == 2;
	}

	private boolean isElevator() {
		return data.kind() == 3;
	}

	private NetEntry network() {
		return networks.isEmpty() ? null : networks.get(Math.floorMod(networkIndex, networks.size()));
	}

	@Override
	protected void init() {
		super.init();
		int x = left + 10;
		int y = top + 26;
		if (data.foreign()) {
			int bw = isBug() ? 110 : 90;
			if (isBug()) {
				button(x, top + panelHeight - 26, bw, 18, Component.translatable("gui.createnetlink.rip_out"), b -> {
					ClientPlayNetworking.send(new NetlinkPackets.DetachBug(data.pos()));
					onClose();
				});
			}
			button(left + panelWidth - 10 - 90, top + panelHeight - 26, 90, 18, Component.translatable("gui.createnetlink.close"), b -> onClose());
			return;
		}

		NetButton net = button(x + 66, y, 170, 16, networkLabel(), b -> {
			networkIndex++;
			b.setMessage(networkLabel());
			chipScroll = 0;
		});
		net.active = networks.size() > 1;

		if (isExtender()) {
			speedBox = field(x + 66, y + 22, 50, String.valueOf(Math.max(1, data.speed())));
			speedBox.setMaxLength(3);
			NetButton inv = button(x + 124, y + 22, 112, 16, invertLabel(), b -> {
				invert = !invert;
				b.setMessage(invertLabel());
			});
			inv.selected = () -> invert;
		} else if (isElevator()) {
			// name only
		} else {
			NetButton post = button(x + 66, y + 22, 82, 16, Component.translatable("gui.createnetlink.post"), b -> listen = false);
			post.selected = () -> !listen;
			NetButton lis = button(x + 154, y + 22, 82, 16, Component.translatable("gui.createnetlink.listen"), b -> listen = true);
			lis.selected = () -> listen;
			lis.active = data.allowListen();
		}

		channelBox = field(x + 66, y + 54, 170, data.channel());
		channelBox.setHint(Component.translatable("gui.createnetlink.channel_hint"));
		setInitialFocus(channelBox);

		int by = top + panelHeight - 26;
		button(x, by, 76, 18, Component.translatable("gui.createnetlink.apply"), b -> apply());
		if (isBug()) {
			button(x + 82, by, 76, 18, Component.translatable("gui.createnetlink.detach"), b -> {
				ClientPlayNetworking.send(new NetlinkPackets.DetachBug(data.pos()));
				onClose();
			});
		}
		if (isExtender()) {
			button(x + 82, by, 76, 18, Component.translatable("gui.createnetlink.extender.remove"), b -> {
				ClientPlayNetworking.send(new NetlinkPackets.RemoveExtender(data.pos()));
				onClose();
			});
		}
		button(left + panelWidth - 10 - 76, by, 76, 18, Component.translatable("gui.createnetlink.cancel"), b -> onClose());
	}

	private Component networkLabel() {
		NetEntry n = network();
		if (n == null) {
			return Component.literal("-");
		}
		return Component.literal("◀ ").append(n.own() ? Component.translatable("gui.createnetlink.my_network") : Component.literal(n.name()))
			.append(" ▶");
	}

	private Component invertLabel() {
		return Component.translatable(invert ? "gui.createnetlink.extender.invert_on" : "gui.createnetlink.extender.invert_off");
	}

	private int speed() {
		if (speedBox == null) {
			return 0;
		}
		try {
			return Math.max(1, Math.min(100, Integer.parseInt(speedBox.getValue().trim())));
		} catch (NumberFormatException e) {
			return Math.max(1, data.speed());
		}
	}

	private void apply() {
		NetEntry n = network();
		if (n == null) {
			return;
		}
		UUID owner = n.owner();
		ClientPlayNetworking.send(new NetlinkPackets.ApplyDeviceConfig(data.kind(), data.pos(), owner, channelBox.getValue(),
			isExtender() || listen, speed(), invert));
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!data.foreign() && (event.key() == 257 || event.key() == 335)) {
			apply();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		if (!data.foreign()) {
			inset(g, left + 10 + 66, top + 26 + 54, 170, 20);
			if (isExtender()) {
				inset(g, left + 10 + 66, top + 26 + 22, 50, 20);
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int x = left + 10;
		int y = top + 26;
		String pos = data.pos().getX() + ", " + data.pos().getY() + ", " + data.pos().getZ();
		g.text(font, pos, left + panelWidth - 10 - font.width(pos), top + 8, TEXT_DIM, false);

		if (data.foreign()) {
			g.text(font, Component.translatable("gui.createnetlink.foreign", data.ownerName()), x, y + 4, WARN, false);
			g.textWithWordWrap(font, Component.translatable(isBug() ? "gui.createnetlink.foreign_bug_hint" : "gui.createnetlink.foreign_hint"),
				x, y + 20, panelWidth - 20, TEXT_DIM);
			if (!data.channel().isEmpty()) {
				g.text(font, Component.translatable("gui.createnetlink.foreign_channel", data.channel()), x, y + 46, TEXT, false);
			}
			return;
		}

		g.text(font, Component.translatable("gui.createnetlink.network"), x, y + 4, TEXT_DIM, false);
		if (isElevator()) {
			g.text(font, Component.translatable("gui.createnetlink.elevator.name"), x, y + 60, TEXT_DIM, false);
			g.textWithWordWrap(font, Component.translatable("gui.createnetlink.elevator.outputs"), x, y + 82, panelWidth - 20, TEXT_DIM);
			return;
		}
		if (isExtender()) {
			g.text(font, Component.translatable("gui.createnetlink.extender.speed"), x, y + 26, TEXT_DIM, false);
			g.text(font, Component.translatable(invert ? "gui.createnetlink.extender.hint_inverted" : "gui.createnetlink.extender.hint"),
				x + 66, y + 42, TEXT_DIM, false);
			g.text(font, Component.translatable("gui.createnetlink.channel"), x, y + 60, TEXT_DIM, false);
			g.text(font, Component.translatable("gui.createnetlink.known_channels"), x, y + 82, TEXT_DIM, false);
			drawChips(g, x, y + 94, panelWidth - 20, 40, mouseX, mouseY);
			return;
		}
		g.text(font, Component.translatable("gui.createnetlink.mode"), x, y + 26, TEXT_DIM, false);
		Component hint = !data.allowListen()
			? Component.translatable("gui.createnetlink.post_only")
			: Component.translatable(listen ? "gui.createnetlink.listen_hint" : "gui.createnetlink.post_hint");
		g.text(font, hint, x + 66, y + 42, !data.allowListen() ? WARN : TEXT_DIM, false);
		g.text(font, Component.translatable("gui.createnetlink.channel"), x, y + 60, TEXT_DIM, false);

		g.text(font, Component.translatable("gui.createnetlink.known_channels"), x, y + 82, TEXT_DIM, false);
		drawChips(g, x, y + 94, panelWidth - 20, 40, mouseX, mouseY);
	}

	private void drawChips(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
		inset(g, x, y, w, h);
		chipRects.clear();
		chipNames.clear();
		NetEntry n = network();
		if (n == null || n.channels().isEmpty()) {
			g.text(font, Component.translatable("gui.createnetlink.no_channels"), x + 6, y + 6, 0xFF4F5A67, false);
			return;
		}
		g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
		int cx = x + 4;
		int cy = y + 4 - chipScroll;
		String current = channelBox.getValue();
		for (String ch : n.channels()) {
			String label = trim(font, ch, w - 20);
			int cw = font.width(label) + 8;
			if (cx + cw > x + w - 4) {
				cx = x + 4;
				cy += 14;
			}
			boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + 12 && mouseY >= y && mouseY < y + h;
			boolean sel = ch.equals(current);
			g.fill(cx, cy, cx + cw, cy + 12, sel ? ACCENT_DARK : hover ? 0xFF283548 : 0xFF1A212C);
			g.outline(cx, cy, cw, 12, sel ? 0xFFB8F6FF : 0xFF3A4659);
			g.text(font, label, cx + 4, cy + 2, sel ? 0xFF0B0F16 : TEXT, false);
			chipRects.add(new int[]{cx, cy, cw, 12});
			chipNames.add(ch);
			cx += cw + 4;
		}
		g.disableScissor();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!data.foreign()) {
			int areaY = top + 26 + 94;
			if (event.y() >= areaY && event.y() < areaY + 40) {
				for (int k = 0; k < chipRects.size(); k++) {
					int[] r = chipRects.get(k);
					if (event.x() >= r[0] && event.x() < r[0] + r[2] && event.y() >= r[1] && event.y() < r[1] + r[3]) {
						channelBox.setValue(chipNames.get(k));
						if (doubleClick) {
							apply();
						}
						return true;
					}
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		chipScroll = Math.max(0, chipScroll - (int) (scrollY * 10));
		return true;
	}
}
