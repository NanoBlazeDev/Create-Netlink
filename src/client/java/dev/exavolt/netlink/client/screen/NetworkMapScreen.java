package dev.exavolt.netlink.client.screen;

import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.client.render.XrayRenderer;
import dev.exavolt.netlink.net.NetlinkPackets;
import dev.exavolt.netlink.net.NetlinkPackets.MapChannel;
import dev.exavolt.netlink.net.NetlinkPackets.MapDevice;
import dev.exavolt.netlink.net.NetlinkPackets.MapNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /network: a live top-down map (with faint terrain relief) of every device on the networks you can reach — each
 * drawn as the mechanism it controls — a channel list showing where each channel's signal comes from (POST) and
 * goes to (LISTEN), and controls to drive a channel by hand.
 */
public class NetworkMapScreen extends NetlinkScreen {
	private static final int ROW = 36;
	private static final int IO_ICONS = 3;

	private List<MapNetwork> networks;
	private int networkIndex;
	private @Nullable String selected;
	private int level = 15;
	private int refresh;
	private int listScroll;

	private double camX;
	private double camZ;
	private double scale = 2.0;
	private boolean dragging;

	private NetButton networkButton;
	private final MapTerrain terrain = new MapTerrain();
	private final Map<String, ItemStack> iconCache = new HashMap<>();

	public NetworkMapScreen(List<MapNetwork> networks) {
		super(Component.translatable("gui.createnetlink.map.title"), 0, 0);
		this.networks = networks;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			camX = mc.player.getX();
			camZ = mc.player.getZ();
		}
	}

	public void update(List<MapNetwork> fresh) {
		String owner = network() == null ? null : network().owner().toString();
		this.networks = fresh;
		if (owner != null) {
			for (int k = 0; k < fresh.size(); k++) {
				if (fresh.get(k).owner().toString().equals(owner)) {
					networkIndex = k;
				}
			}
		}
		if (networkButton != null) {
			networkButton.setMessage(networkLabel());
			networkButton.active = networks.size() > 1;
		}
	}

	@Override
	public void removed() {
		super.removed();
		terrain.close();
	}

	private @Nullable MapNetwork network() {
		return networks.isEmpty() ? null : networks.get(Math.floorMod(networkIndex, networks.size()));
	}

	private @Nullable MapChannel selectedChannel() {
		MapNetwork n = network();
		if (n == null || selected == null) {
			return null;
		}
		for (MapChannel c : n.channels()) {
			if (c.channel().equals(selected)) {
				return c;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ icons

	private static @Nullable Block block(String id) {
		Identifier rl = Identifier.tryParse(id);
		if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
			return null;
		}
		return BuiltInRegistries.BLOCK.getValue(rl);
	}

	/** The mechanism's item, falling back to the device itself when the block has no item form. */
	private ItemStack icon(MapDevice d) {
		return iconCache.computeIfAbsent(d.block() + "|" + d.kind(), k -> {
			Block b = block(d.block());
			if (b != null && b != Blocks.AIR) {
				ItemStack stack = new ItemStack(b.asItem());
				if (!stack.isEmpty()) {
					return stack;
				}
			}
			return badge(d);
		});
	}

	private static ItemStack badge(MapDevice d) {
		return new ItemStack(d.kind() == 1 ? NetlinkRegistry.BUG : d.kind() == 2 ? NetlinkRegistry.WALL_EXTENDER_ITEM
			: d.kind() == 3 ? NetlinkRegistry.ADVANCED_ELEVATOR_PULLEY_ITEM : NetlinkRegistry.ADVANCED_REDSTONE_LINK_ITEM);
	}

	private static Component mechanismName(MapDevice d) {
		Block b = block(d.block());
		return b != null && b != Blocks.AIR ? b.getName() : Component.translatable(d.kind() == 1 ? "hud.createnetlink.bug" : d.kind() == 2 ? "hud.createnetlink.extender" : "hud.createnetlink.link");
	}

	private static int modeColor(MapDevice d) {
		return d.channel().isEmpty() ? XrayRenderer.COLOR_IDLE
			: d.kind() == 1 ? XrayRenderer.COLOR_BUG : d.kind() == 2 ? XrayRenderer.COLOR_EXTENDER : d.kind() == 3 ? XrayRenderer.COLOR_ELEVATOR : d.listen() ? XrayRenderer.COLOR_LISTEN : XrayRenderer.COLOR_POST;
	}

	/** Item icon of the mechanism at size px, with the device badge in the corner. */
	private void deviceIcon(GuiGraphicsExtractor g, MapDevice d, int x, int y, int size, boolean withBadge) {
		float s = size / 16f;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(s, s);
		g.item(icon(d), 0, 0);
		g.pose().popMatrix();
		if (withBadge) {
			float bs = size / 32f;
			g.pose().pushMatrix();
			g.pose().translate(x + size / 2f, y + size / 2f);
			g.pose().scale(bs, bs);
			g.item(badge(d), 0, 0);
			g.pose().popMatrix();
		}
	}

	// ------------------------------------------------------------------ layout

	private int mapX() {
		return left + 10;
	}

	private int mapY() {
		return top + 40;
	}

	private int mapW() {
		return (int) ((panelWidth - 28) * 0.6);
	}

	private int mapH() {
		return panelHeight - 50;
	}

	private int listX() {
		return mapX() + mapW() + 8;
	}

	private int listW() {
		return left + panelWidth - 10 - listX();
	}

	private int controlsH() {
		return 84;
	}

	private int listH() {
		return mapH() - controlsH() - 6;
	}

	@Override
	protected void init() {
		panelWidth = Math.max(340, width - 24);
		panelHeight = Math.max(220, height - 24);
		super.init();

		networkButton = button(left + panelWidth - 10 - 160, top + 5, 160, 14, networkLabel(), b -> {
			networkIndex++;
			b.setMessage(networkLabel());
			selected = null;
			listScroll = 0;
		});
		networkButton.active = networks.size() > 1;
		button(left + panelWidth - 10 - 160 - 6 - 74, top + 5, 74, 14, Component.translatable("gui.createnetlink.scripts.open"), b -> {
			MapNetwork n = network();
			if (n != null) {
				ScriptScreen.openFor(n.owner(), n.name());
			}
		});

		int cx = listX();
		int cy = mapY() + listH() + 6 + 22;
		int cw = listW();
		int bw = (cw - 8) / 3;
		button(cx, cy, bw, 16, Component.translatable("gui.createnetlink.map.pulse"), b -> send(NetlinkPackets.ACTION_PULSE, level));
		button(cx + bw + 4, cy, bw, 16, Component.translatable("gui.createnetlink.map.hold"), b -> send(NetlinkPackets.ACTION_SET, level));
		button(cx + 2 * (bw + 4), cy, bw, 16, Component.translatable("gui.createnetlink.map.release"), b -> send(NetlinkPackets.ACTION_CLEAR, 0));
		button(cx, cy + 22, 20, 16, Component.literal("-"), b -> level = Math.max(1, level - 1));
		button(cx + 70, cy + 22, 20, 16, Component.literal("+"), b -> level = Math.min(15, level + 1));
		button(cx + 96, cy + 22, 34, 16, Component.literal("15"), b -> level = 15);
	}

	private Component networkLabel() {
		MapNetwork n = network();
		if (n == null) {
			return Component.literal("-");
		}
		return Component.literal("◀ ").append(n.own() ? Component.translatable("gui.createnetlink.my_network") : Component.literal(n.name()))
			.append(" ▶");
	}

	private void send(int action, int value) {
		MapNetwork n = network();
		if (n == null || selected == null) {
			return;
		}
		ClientPlayNetworking.send(new NetlinkPackets.SetChannel(n.owner(), selected, action, value));
	}

	@Override
	public void tick() {
		super.tick();
		if (++refresh >= 10) {
			refresh = 0;
			ClientPlayNetworking.send(new NetlinkPackets.RequestMap());
		}
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		inset(g, mapX(), mapY(), mapW(), mapH());
		inset(g, listX(), mapY(), listW(), listH());
		inset(g, listX(), mapY() + listH() + 6, listW(), controlsH());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		MapNetwork n = network();
		if (n != null) {
			Component info = n.authorized().isEmpty()
				? Component.translatable("gui.createnetlink.map.owner_only", n.name())
				: Component.translatable("gui.createnetlink.map.owner_auth", n.name(), String.join(", ", n.authorized()));
			g.text(font, Component.literal(trim(font, info.getString(), panelWidth - 20)), left + 10, top + 26, TEXT_DIM, false);
		}
		drawMap(g, mouseX, mouseY);
		List<MapDevice> popup = drawList(g, mouseX, mouseY);
		drawControls(g);
		if (popup != null) {
			drawIoPopup(g, popup, mouseX, mouseY);
		}
	}

	private String currentDim() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? "" : mc.level.dimension().identifier().toString();
	}

	private int gridStep() {
		int step = 1;
		while (step * scale < 24 && step < 1 << 20) {
			step *= 2;
		}
		return step;
	}

	private void drawMap(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x0 = mapX() + 2, y0 = mapY() + 2, x1 = mapX() + mapW() - 2, y1 = mapY() + mapH() - 2;
		double cx = (x0 + x1) / 2.0, cy = (y0 + y1) / 2.0;
		g.enableScissor(x0, y0, x1, y1);

		terrain.draw(g, x0, y0, x1 - x0, y1 - y0, camX, camZ, scale);

		int step = gridStep();
		double worldLeft = camX - (cx - x0) / scale;
		double worldTop = camZ - (cy - y0) / scale;
		long startX = (long) Math.floor(worldLeft / step) * step;
		long startZ = (long) Math.floor(worldTop / step) * step;
		for (long wx = startX; ; wx += step) {
			int sx = (int) Math.round(cx + (wx - camX) * scale);
			if (sx > x1) {
				break;
			}
			g.fill(sx, y0, sx + 1, y1, wx % (step * 8L) == 0 ? 0x5530485E : 0x30304050);
		}
		for (long wz = startZ; ; wz += step) {
			int sy = (int) Math.round(cy + (wz - camZ) * scale);
			if (sy > y1) {
				break;
			}
			g.fill(x0, sy, x1, sy + 1, wz % (step * 8L) == 0 ? 0x5530485E : 0x30304050);
		}

		MapDevice hover = null;
		MapNetwork n = network();
		String dim = currentDim();
		int elsewhere = 0;
		int size = (int) Mth.clamp(scale * 6, 8, 16);
		if (n != null) {
			for (MapDevice d : n.devices()) {
				if (!d.dim().equals(dim)) {
					elsewhere++;
					continue;
				}
				int sx = (int) Math.round(cx + (d.pos().getX() + 0.5 - camX) * scale);
				int sy = (int) Math.round(cy + (d.pos().getZ() + 0.5 - camZ) * scale);
				if (sx < x0 - size || sx > x1 + size || sy < y0 - size || sy > y1 + size) {
					continue;
				}
				int ix = sx - size / 2, iy = sy - size / 2;
				int rgb = 0xFF000000 | modeColor(d);
				boolean sel = selected != null && selected.equals(d.channel());
				g.fill(ix - 1, iy - 1, ix + size + 1, iy + size + 1, d.value() > 0 ? (0xC0000000 | modeColor(d)) : 0xA00B0F16);
				g.outline(ix - 2, iy - 2, size + 4, size + 4, sel ? 0xFFFFFFFF : rgb);
				deviceIcon(g, d, ix, iy, size, true);
				if (mouseX >= ix - 2 && mouseX < ix + size + 2 && mouseY >= iy - 2 && mouseY < iy + size + 2) {
					hover = d;
				}
			}
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			int px = (int) Math.round(cx + (mc.player.getX() - camX) * scale);
			int py = (int) Math.round(cy + (mc.player.getZ() - camZ) * scale);
			float yaw = mc.player.getYRot() * Mth.DEG_TO_RAD;
			int dx = (int) Math.round(-Math.sin(yaw) * 7);
			int dz = (int) Math.round(Math.cos(yaw) * 7);
			for (int k = 0; k <= 7; k++) {
				g.fill(px + dx * k / 7, py + dz * k / 7, px + dx * k / 7 + 1, py + dz * k / 7 + 1, 0xFFFFFFFF);
			}
			g.fill(px - 2, py - 2, px + 3, py + 3, 0xFFFBCC68);
		}
		g.disableScissor();

		g.text(font, "N", (int) cx - 2, y0 + 3, ACCENT, true);
		if (mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1) {
			int wx = (int) Math.floor(camX + (mouseX - cx) / scale);
			int wz = (int) Math.floor(camZ + (mouseY - cy) / scale);
			g.text(font, "X " + wx + "  Z " + wz, x0 + 4, y1 - 11, TEXT, true);
		}
		String scaleText = step + " m";
		g.text(font, scaleText, x1 - 4 - font.width(scaleText), y1 - 11, TEXT, true);
		if (elsewhere > 0) {
			Component other = Component.translatable("gui.createnetlink.map.elsewhere", elsewhere);
			g.text(font, other, x1 - 4 - font.width(other), y0 + 3, TEXT_DIM, true);
		}
		if (n == null || n.devices().isEmpty()) {
			g.centeredText(font, Component.translatable("gui.createnetlink.map.empty"), (int) cx, (int) cy - 4, TEXT_DIM);
		}

		if (hover != null) {
			List<Component> lines = new ArrayList<>();
			lines.add(mechanismName(hover));
			lines.add(Component.translatable(hover.kind() == 1 ? "hud.createnetlink.bug" : hover.kind() == 2 ? "hud.createnetlink.extender" : "hud.createnetlink.link")
				.append(Component.literal("  " + (hover.channel().isEmpty() ? "—" : hover.channel()))));
			lines.add(Component.translatable(hover.listen() ? "gui.createnetlink.listen" : "gui.createnetlink.post")
				.append(Component.literal("  " + hover.value() + "/15")));
			lines.add(Component.literal(hover.pos().getX() + ", " + hover.pos().getY() + ", " + hover.pos().getZ()));
			g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		}
	}

	private List<MapDevice> devicesOf(MapNetwork n, String channel, boolean listen) {
		List<MapDevice> out = new ArrayList<>();
		for (MapDevice d : n.devices()) {
			if (d.channel().equals(channel) && d.listen() == listen) {
				out.add(d);
			}
		}
		return out;
	}

	/** Draws the channel list; returns the device list to pop up when the IO strip of a row is hovered. */
	private @Nullable List<MapDevice> drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = listX() + 2, y = mapY() + 2, w = listW() - 4, h = listH() - 4;
		MapNetwork n = network();
		g.text(font, Component.translatable("gui.createnetlink.map.channels"), x + 4, y + 3, ACCENT, false);
		int listTop = y + 14;
		List<MapDevice> popup = null;
		g.enableScissor(x, listTop, x + w, y + h);
		if (n == null || n.channels().isEmpty()) {
			g.text(font, Component.translatable("gui.createnetlink.no_channels"), x + 4, listTop + 4, 0xFF4F5A67, false);
		} else {
			int ry = listTop - listScroll;
			for (MapChannel c : n.channels()) {
				if (ry + ROW < listTop || ry > y + h) {
					ry += ROW;
					continue;
				}
				boolean inside = mouseY >= listTop && mouseY < y + h;
				boolean sel = c.channel().equals(selected);
				boolean hover = inside && mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW - 2;
				g.fill(x + 2, ry, x + w - 2, ry + ROW - 2, sel ? 0xFF16364A : hover ? 0xFF1C2635 : 0xFF121922);
				if (sel) {
					g.fill(x + 2, ry, x + 4, ry + ROW - 2, ACCENT_DARK);
				}
				g.text(font, trim(font, c.channel(), w - 40), x + 8, ry + 3, TEXT, false);
				String v = c.manual() >= 0 ? "M" + c.manual() : String.valueOf(c.value());
				g.text(font, v, x + w - 6 - font.width(v), ry + 3, c.manual() >= 0 ? 0xFFFBCC68 : TEXT, false);
				signalBar(g, x + 8, ry + 13, w - 16, 3, c.value(), c.value() > 0 ? 0xFF3DF5A8 : 0xFF22B6DC);

				List<MapDevice> ins = devicesOf(n, c.channel(), false);
				List<MapDevice> outs = devicesOf(n, c.channel(), true);
				int ioY = ry + 19;
				int half = (w - 12) / 2;
				int inEnd = ioStrip(g, Component.translatable("gui.createnetlink.map.in"), ins, x + 8, ioY, half);
				int outEnd = ioStrip(g, Component.translatable("gui.createnetlink.map.out"), outs, x + 8 + half, ioY, half);
				if (inside && mouseY >= ioY - 1 && mouseY < ioY + 13) {
					if (mouseX >= x + 8 && mouseX < inEnd && !ins.isEmpty()) {
						popup = new ArrayList<>();
						popup.addAll(ins);
						popup.addAll(outs);
					} else if (mouseX >= x + 8 + half && mouseX < outEnd && !outs.isEmpty()) {
						popup = new ArrayList<>();
						popup.addAll(ins);
						popup.addAll(outs);
					}
				}
				ry += ROW;
			}
		}
		g.disableScissor();
		return popup;
	}

	/** "IN ▣▣▣…" — up to three mechanism icons, then an ellipsis. Returns the strip's right edge. */
	private int ioStrip(GuiGraphicsExtractor g, Component label, List<MapDevice> devices, int x, int y, int maxW) {
		g.text(font, label, x, y + 2, TEXT_DIM, false);
		int ix = x + font.width(label) + 3;
		if (devices.isEmpty()) {
			g.text(font, "—", ix, y + 2, 0xFF4F5A67, false);
			return ix + font.width("—");
		}
		int shown = Math.min(IO_ICONS, devices.size());
		for (int k = 0; k < shown; k++) {
			deviceIcon(g, devices.get(k), ix, y, 12, false);
			ix += 13;
		}
		if (devices.size() > IO_ICONS) {
			g.text(font, "…", ix + 1, y + 2, TEXT, false);
			ix += font.width("…") + 2;
		}
		return Math.min(ix, x + maxW);
	}

	/** Full list of a channel's inputs and outputs, sized to its content. */
	private void drawIoPopup(GuiGraphicsExtractor g, List<MapDevice> devices, int mouseX, int mouseY) {
		List<MapDevice> ins = new ArrayList<>();
		List<MapDevice> outs = new ArrayList<>();
		for (MapDevice d : devices) {
			(d.listen() ? outs : ins).add(d);
		}
		int lineH = 14;
		int maxLines = Math.max(4, (height - 40) / lineH - 3);
		List<Object> lines = new ArrayList<>();
		lines.add(Component.translatable("gui.createnetlink.map.inputs", ins.size()));
		lines.addAll(ins);
		lines.add(Component.translatable("gui.createnetlink.map.outputs", outs.size()));
		lines.addAll(outs);
		int extra = 0;
		if (lines.size() > maxLines) {
			extra = lines.size() - (maxLines - 1);
			lines = new ArrayList<>(lines.subList(0, maxLines - 1));
		}
		int w = 60;
		for (Object o : lines) {
			if (o instanceof Component c) {
				w = Math.max(w, font.width(c) + 10);
			} else if (o instanceof MapDevice d) {
				w = Math.max(w, 18 + font.width(deviceLine(d)) + 10);
			}
		}
		int h = lines.size() * lineH + 8 + (extra > 0 ? lineH : 0);
		int px = mouseX + 12;
		int py = mouseY + 8;
		if (px + w > width - 4) {
			px = mouseX - 12 - w;
		}
		if (py + h > height - 4) {
			py = Math.max(4, height - 4 - h);
		}

		g.nextStratum();
		panel(g, px, py, w, h);
		int ly = py + 4;
		for (Object o : lines) {
			if (o instanceof Component c) {
				g.text(font, c, px + 6, ly + 3, ACCENT, false);
			} else if (o instanceof MapDevice d) {
				deviceIcon(g, d, px + 6, ly, 12, false);
				g.fill(px + 6 + 13, ly + 2, px + 6 + 15, ly + 10, 0xFF000000 | modeColor(d));
				g.text(font, deviceLine(d), px + 6 + 18, ly + 3, d.value() > 0 ? TEXT : TEXT_DIM, false);
			}
			ly += lineH;
		}
		if (extra > 0) {
			g.text(font, Component.translatable("gui.createnetlink.map.more", extra), px + 6, ly + 3, TEXT_DIM, false);
		}
	}

	private Component deviceLine(MapDevice d) {
		String where = d.pos().getX() + " " + d.pos().getY() + " " + d.pos().getZ();
		Component dimNote = d.dim().equals(currentDim()) ? Component.empty() : Component.translatable("gui.createnetlink.map.other_dim");
		return Component.empty().append(mechanismName(d)).append(Component.literal("  " + where + "  " + d.value())).append(dimNote);
	}

	private void drawControls(GuiGraphicsExtractor g) {
		int x = listX() + 6;
		int y = mapY() + listH() + 6 + 6;
		MapChannel c = selectedChannel();
		if (c == null) {
			g.text(font, Component.translatable("gui.createnetlink.map.select"), x, y, 0xFF4F5A67, false);
		} else {
			g.text(font, trim(font, c.channel(), listW() - 60), x, y, ACCENT, false);
			String v = c.value() + "/15";
			g.text(font, v, listX() + listW() - 6 - font.width(v), y, TEXT, false);
		}
		int ly = mapY() + listH() + 6 + 22 + 22;
		g.centeredText(font, Component.literal(String.valueOf(level)), listX() + 45, ly + 4, TEXT);
		g.text(font, Component.translatable("gui.createnetlink.map.level"), listX() + 136, ly + 4, TEXT_DIM, false);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		if (inMap(mx, my)) {
			MapDevice d = deviceAt(mx, my);
			if (d != null && !d.channel().isEmpty()) {
				selected = d.channel();
			} else {
				dragging = true;
			}
			return true;
		}
		int x = listX() + 2, y = mapY() + 16, w = listW() - 4, h = listH() - 18;
		if (mx >= x && mx < x + w && my >= y && my < y + h) {
			MapNetwork n = network();
			if (n != null) {
				int idx = (int) ((my - y + listScroll) / ROW);
				if (idx >= 0 && idx < n.channels().size()) {
					selected = n.channels().get(idx).channel();
					if (doubleClick) {
						send(NetlinkPackets.ACTION_PULSE, level);
					}
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			camX -= dx / scale;
			camZ -= dy / scale;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (inMap(mouseX, mouseY)) {
			double cx = mapX() + mapW() / 2.0, cy = mapY() + mapH() / 2.0;
			double wx = camX + (mouseX - cx) / scale;
			double wz = camZ + (mouseY - cy) / scale;
			scale = Mth.clamp(scale * (scrollY > 0 ? 1.25 : 0.8), 0.05, 24.0);
			camX = wx - (mouseX - cx) / scale;
			camZ = wz - (mouseY - cy) / scale;
			return true;
		}
		MapNetwork n = network();
		int max = n == null ? 0 : Math.max(0, n.channels().size() * ROW - (listH() - 18));
		listScroll = Mth.clamp(listScroll - (int) (scrollY * 14), 0, max);
		return true;
	}

	private boolean inMap(double mx, double my) {
		return mx >= mapX() && mx < mapX() + mapW() && my >= mapY() && my < mapY() + mapH();
	}

	private @Nullable MapDevice deviceAt(double mx, double my) {
		MapNetwork n = network();
		if (n == null) {
			return null;
		}
		double cx = mapX() + 2 + (mapW() - 4) / 2.0, cy = mapY() + 2 + (mapH() - 4) / 2.0;
		String dim = currentDim();
		MapDevice best = null;
		double bestD = 100;
		for (MapDevice d : n.devices()) {
			if (!d.dim().equals(dim)) {
				continue;
			}
			double sx = cx + (d.pos().getX() + 0.5 - camX) * scale;
			double sy = cy + (d.pos().getZ() + 0.5 - camZ) * scale;
			double dd = (sx - mx) * (sx - mx) + (sy - my) * (sy - my);
			if (dd < bestD) {
				bestD = dd;
				best = d;
			}
		}
		return best;
	}
}
