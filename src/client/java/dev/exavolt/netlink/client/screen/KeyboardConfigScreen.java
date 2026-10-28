package dev.exavolt.netlink.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.exavolt.netlink.item.KeyboardConfig;
import dev.exavolt.netlink.net.NetlinkPackets;
import dev.exavolt.netlink.net.NetlinkPackets.NetEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Edit the Netlink Keyboard's bind list: any key → any channel, as many rows as you like (up to 48). */
public class KeyboardConfigScreen extends NetlinkScreen {
	private static final int ROW_H = 22;
	private static final int VISIBLE = 7;

	private static final class Row {
		int key;
		String channel;
		int action = KeyboardConfig.POWER;
		int ticks = KeyboardConfig.DEFAULT_TICKS;

		Row(int key, String channel) {
			this.key = key;
			this.channel = channel;
		}
	}

	private static final String[] ACTION_KEYS = {
		"gui.createnetlink.keyboard.action.power", "gui.createnetlink.keyboard.action.toggle",
		"gui.createnetlink.keyboard.action.pulse", "gui.createnetlink.keyboard.action.toggle_pulse"
	};
	private static final int KEY_W = 70;
	private static final int ACTION_W = 78;
	private static final int TICKS_W = 34;

	private int channelW() {
		return panelWidth - 20 - KEY_W - 6 - ACTION_W - 4 - TICKS_W - 4 - 18 - 4;
	}

	private int channelX() {
		return left + 10 + KEY_W + 6;
	}

	private int actionX() {
		return channelX() + channelW() + 4;
	}

	private int ticksX() {
		return actionX() + ACTION_W + 4;
	}

	private final NetlinkPackets.OpenKeyboardConfig data;
	private final List<NetEntry> networks;
	private final List<Row> rows = new ArrayList<>();
	private int networkIndex;
	private int scroll;
	private int listening = -1;
	private int lastFocused = -1;
	private final List<EditBox> boxes = new ArrayList<>();
	private int chipScroll;
	private final List<int[]> chipRects = new ArrayList<>();
	private final List<String> chipNames = new ArrayList<>();

	public KeyboardConfigScreen(NetlinkPackets.OpenKeyboardConfig data) {
		super(Component.translatable("gui.createnetlink.keyboard.title"), 400, 310);
		this.data = data;
		this.networks = data.networks();
		for (KeyboardConfig.Bind b : data.config().binds()) {
			Row r = new Row(b.key(), b.channel());
			r.action = b.action();
			r.ticks = b.ticks();
			rows.add(r);
		}
		if (data.config().owner().isPresent()) {
			for (int k = 0; k < networks.size(); k++) {
				if (networks.get(k).owner().equals(data.config().owner().get())) {
					networkIndex = k;
				}
			}
		}
	}

	private NetEntry network() {
		return networks.isEmpty() ? null : networks.get(Math.floorMod(networkIndex, networks.size()));
	}

	private int listTop() {
		return top + 62;
	}

	private int chipsTop() {
		return listTop() + VISIBLE * ROW_H + 34;
	}

	@Override
	protected void init() {
		super.init();
		boxes.clear();
		int x = left + 10;
		NetButton net = button(x + 66, top + 26, panelWidth - 86, 16, networkLabel(), b -> {
			networkIndex++;
			b.setMessage(networkLabel());
			chipScroll = 0;
		});
		net.active = networks.size() > 1;

		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - VISIBLE)));
		for (int i = scroll; i < Math.min(rows.size(), scroll + VISIBLE); i++) {
			final int idx = i;
			int y = listTop() + (i - scroll) * ROW_H;
			Row row = rows.get(i);
			Component label = listening == idx
				? Component.translatable("gui.createnetlink.keyboard.press")
				: row.key < 0 ? Component.translatable("gui.createnetlink.keyboard.no_key") : InputConstants.Type.KEYSYM.getOrCreate(row.key).getDisplayName();
			NetButton keyButton = button(x, y, KEY_W, 18, label, b -> {
				listening = listening == idx ? -1 : idx;
				rebuildWidgets();
			});
			keyButton.selected = () -> listening == idx;

			EditBox box = field(channelX(), y, channelW(), row.channel);
			box.setHint(Component.translatable("gui.createnetlink.channel_hint"));
			box.setResponder(v -> row.channel = v);
			boxes.add(box);

			NetButton action = button(actionX(), y, ACTION_W, 18, Component.translatable(ACTION_KEYS[row.action]), b -> {
				row.action = (row.action + 1) % KeyboardConfig.ACTIONS;
				rebuildWidgets();
			});
			action.setTooltip(Tooltip.create(
				Component.translatable(ACTION_KEYS[row.action] + ".desc")));

			if (row.action == KeyboardConfig.PULSE || row.action == KeyboardConfig.TOGGLE_PULSE) {
				EditBox ticks = field(ticksX(), y, TICKS_W, String.valueOf(row.ticks));
				ticks.setMaxLength(4);
				ticks.setResponder(v -> {
					try {
						row.ticks = Math.max(1, Math.min(KeyboardConfig.MAX_TICKS, Integer.parseInt(v)));
					} catch (NumberFormatException e) {
						row.ticks = KeyboardConfig.DEFAULT_TICKS;
					}
				});
			}

			button(left + panelWidth - 10 - 18, y, 18, 18, Component.literal("\u2715"), b -> {
				rows.remove(idx);
				if (listening == idx) {
					listening = -1;
				}
				rebuildWidgets();
			});
		}

		int addY = listTop() + VISIBLE * ROW_H + 4;
		NetButton add = button(x, addY, 100, 16, Component.translatable("gui.createnetlink.keyboard.add"), b -> {
			rows.add(new Row(-1, ""));
			listening = rows.size() - 1;
			scroll = Math.max(0, rows.size() - VISIBLE);
			rebuildWidgets();
		});
		add.active = rows.size() < KeyboardConfig.MAX_BINDS;

		int by = top + panelHeight - 26;
		button(x, by, 76, 18, Component.translatable("gui.createnetlink.apply"), b -> apply());
		button(x + 82, by, 76, 18, Component.translatable("gui.createnetlink.clear_all"), b -> {
			rows.clear();
			listening = -1;
			rebuildWidgets();
		});
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

	private void apply() {
		NetEntry n = network();
		List<KeyboardConfig.Bind> binds = new ArrayList<>();
		for (Row r : rows) {
			if (r.key >= 0 && !r.channel.isBlank()) {
				binds.add(new KeyboardConfig.Bind(r.key, r.channel.trim(), r.action, r.ticks));
			}
		}
		ClientPlayNetworking.send(new NetlinkPackets.ApplyKeyboardConfig(data.hand(),
			new KeyboardConfig(n == null ? Optional.empty() : Optional.of(n.owner()), binds)));
		onClose();
	}

	@Override
	public void tick() {
		super.tick();
		for (int k = 0; k < boxes.size(); k++) {
			if (boxes.get(k).isFocused()) {
				lastFocused = scroll + k;
			}
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (listening >= 0 && listening < rows.size()) {
			if (event.key() != GLFW.GLFW_KEY_ESCAPE && event.key() >= GLFW.GLFW_KEY_SPACE) {
				rows.get(listening).key = event.key();
			}
			listening = -1;
			rebuildWidgets();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			apply();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		int x = left + 10;
		for (int i = scroll; i < Math.min(rows.size(), scroll + VISIBLE); i++) {
			int y = listTop() + (i - scroll) * ROW_H;
			inset(g, channelX(), y, channelW(), 18);
			Row r = rows.get(i);
			if (r.action == KeyboardConfig.PULSE || r.action == KeyboardConfig.TOGGLE_PULSE) {
				inset(g, ticksX(), y, TICKS_W, 18);
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int x = left + 10;
		g.text(font, Component.translatable("gui.createnetlink.network"), x, top + 30, TEXT_DIM, false);
		g.text(font, Component.translatable("gui.createnetlink.keyboard.key"), x + 4, top + 50, TEXT_DIM, false);
		g.text(font, Component.translatable("gui.createnetlink.channel"), channelX() + 4, top + 50, TEXT_DIM, false);
		g.text(font, Component.translatable("gui.createnetlink.keyboard.action"), actionX() + 4, top + 50, TEXT_DIM, false);
		g.text(font, Component.translatable("gui.createnetlink.keyboard.ticks"), ticksX() + 2, top + 50, TEXT_DIM, false);
		String count = rows.size() + "/" + KeyboardConfig.MAX_BINDS;
		g.text(font, count, left + panelWidth - 10 - font.width(count), top + 30 + 20 + VISIBLE * ROW_H + 18, TEXT_DIM, false);
		if (rows.isEmpty()) {
			g.text(font, Component.translatable("gui.createnetlink.keyboard.empty"), x + 4, listTop() + 6, 0xFF4F5A67, false);
		}
		if (rows.size() > VISIBLE) {
			int trackTop = listTop();
			int trackH = VISIBLE * ROW_H - 4;
			int thumbH = Math.max(12, trackH * VISIBLE / rows.size());
			int thumbY = trackTop + (trackH - thumbH) * scroll / Math.max(1, rows.size() - VISIBLE);
			g.fill(left + panelWidth - 6, trackTop, left + panelWidth - 4, trackTop + trackH, 0xFF1A212C);
			g.fill(left + panelWidth - 6, thumbY, left + panelWidth - 4, thumbY + thumbH, ACCENT_DARK);
		}
		g.text(font, Component.translatable("gui.createnetlink.known_channels_click"), x, chipsTop() - 11, TEXT_DIM, false);
		drawChips(g, x, chipsTop(), panelWidth - 20, 30, mouseX, mouseY);
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
		for (String ch : n.channels()) {
			String label = trim(font, ch, w - 20);
			int cw = font.width(label) + 8;
			if (cx + cw > x + w - 4) {
				cx = x + 4;
				cy += 14;
			}
			boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + 12 && mouseY >= y && mouseY < y + h;
			g.fill(cx, cy, cx + cw, cy + 12, hover ? 0xFF283548 : 0xFF1A212C);
			g.outline(cx, cy, cw, 12, 0xFF3A4659);
			g.text(font, label, cx + 4, cy + 2, TEXT, false);
			chipRects.add(new int[]{cx, cy, cw, 12});
			chipNames.add(ch);
			cx += cw + 4;
		}
		g.disableScissor();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int areaY = chipsTop();
		if (event.y() >= areaY && event.y() < areaY + 30) {
			for (int k = 0; k < chipRects.size(); k++) {
				int[] r = chipRects.get(k);
				if (event.x() >= r[0] && event.x() < r[0] + r[2] && event.y() >= r[1] && event.y() < r[1] + r[3]) {
					int target = lastFocused >= 0 && lastFocused < rows.size() ? lastFocused : -1;
					if (target < 0) {
						rows.add(new Row(-1, chipNames.get(k)));
						listening = rows.size() - 1;
						scroll = Math.max(0, rows.size() - VISIBLE);
					} else {
						rows.get(target).channel = chipNames.get(k);
					}
					rebuildWidgets();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseY >= chipsTop()) {
			chipScroll = Math.max(0, chipScroll - (int) (scrollY * 10));
			return true;
		}
		int max = Math.max(0, rows.size() - VISIBLE);
		int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
		if (next != scroll) {
			scroll = next;
			rebuildWidgets();
		}
		return true;
	}
}
