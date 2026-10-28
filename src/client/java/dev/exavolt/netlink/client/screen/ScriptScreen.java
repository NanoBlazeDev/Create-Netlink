package dev.exavolt.netlink.client.screen;

import dev.exavolt.netlink.net.NetlinkPackets;
import dev.exavolt.netlink.net.NetlinkPackets.ScriptInfo;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Netlink Scripts: write Luau that runs on the server and drives the network. Script list on the left, editor in the
 * middle, live console below. Ctrl+S saves, Ctrl+Enter runs.
 */
public class ScriptScreen extends NetlinkScreen {
	private static @Nullable ScriptScreen open;

	private static final int LIST_W = 140;
	private static final int ROW_H = 18;
	private static final Pattern ERROR_LINE = Pattern.compile(":(\\d+)[: ]");

	private final UUID owner;
	private String ownerName = "";
	private List<ScriptInfo> scripts = new ArrayList<>();
	private String selected = "";
	private List<String> log = new ArrayList<>();
	private boolean dirty;
	private boolean loadedCode;
	private int refresh;
	private int listScroll;
	private int logScroll;
	private boolean followLog = true;

	private CodeEditor editor;
	private EditBox nameBox;

	public ScriptScreen(UUID owner, String ownerName) {
		super(Component.translatable("gui.createnetlink.scripts.title"), 0, 0);
		this.owner = owner;
		this.ownerName = ownerName;
	}

	public static void receive(NetlinkPackets.ScriptState state) {
		if (open != null && open.owner.equals(state.owner())) {
			open.update(state);
		}
	}

	@Override
	protected void init() {
		panelWidth = Math.max(420, width - 20);
		panelHeight = Math.max(260, height - 20);
		super.init();
		open = this;
		int ex = left + 10 + LIST_W + 8;
		int ew = left + panelWidth - 10 - ex;
		int consoleH = Math.max(60, panelHeight / 4);
		int ey = top + 48;
		int eh = top + panelHeight - 10 - consoleH - 18 - ey;

		String code = editor != null ? editor.getText() : "";
		editor = new CodeEditor(font, ex, ey, ew, eh);
		editor.setText(code);
		editor.setOnChange(() -> dirty = true);
		addRenderableWidget(editor);

		String name = nameBox != null ? nameBox.getValue() : "";
		nameBox = field(ex, top + 26, 150, name);
		nameBox.setMaxLength(32);
		nameBox.setHint(Component.translatable("gui.createnetlink.scripts.name_hint"));
		nameBox.setResponder(s -> dirty = true);

		int bx = ex + 156;
		button(bx, top + 27, 60, 16, Component.translatable("gui.createnetlink.scripts.run"), b -> act(NetlinkPackets.SCRIPT_RUN));
		button(bx + 64, top + 27, 60, 16, Component.translatable("gui.createnetlink.scripts.stop"), b -> act(NetlinkPackets.SCRIPT_STOP));
		button(bx + 128, top + 27, 60, 16, Component.translatable("gui.createnetlink.scripts.save"), b -> act(NetlinkPackets.SCRIPT_SAVE));

		int lx = left + 10;
		int by = top + panelHeight - 10 - 18;
		button(lx, by, (LIST_W - 4) / 2, 18, Component.translatable("gui.createnetlink.scripts.new"), b -> newScript());
		button(lx + (LIST_W - 4) / 2 + 4, by, (LIST_W - 4) / 2, 18, Component.translatable("gui.createnetlink.scripts.delete"),
			b -> act(NetlinkPackets.SCRIPT_DELETE));
		button(left + panelWidth - 10 - 50, top + panelHeight - 10 - consoleH - 2 - 12, 50, 12,
			Component.translatable("gui.createnetlink.scripts.clear"), b -> act(NetlinkPackets.SCRIPT_CLEAR_LOG));

		if (!loadedCode) {
			ClientPlayNetworking.send(new NetlinkPackets.ScriptRequest(owner, selected, true));
		}
		setInitialFocus(editor);
	}

	private void newScript() {
		saveIfDirty();
		selected = "";
		loadedCode = false;
		ClientPlayNetworking.send(new NetlinkPackets.ScriptAction(owner, NetlinkPackets.SCRIPT_SAVE, "", "Script" + (scripts.size() + 1), ""));
	}

	private void act(int action) {
		if (action == NetlinkPackets.SCRIPT_DELETE) {
			if (selected.isEmpty()) {
				return;
			}
			loadedCode = false;
		}
		if (selected.isEmpty() && action != NetlinkPackets.SCRIPT_SAVE && action != NetlinkPackets.SCRIPT_RUN) {
			return;
		}
		String name = nameBox.getValue().isBlank() ? "Script" : nameBox.getValue();
		ClientPlayNetworking.send(new NetlinkPackets.ScriptAction(owner, action, selected, name, editor.getText()));
		if (action == NetlinkPackets.SCRIPT_SAVE || action == NetlinkPackets.SCRIPT_RUN) {
			dirty = false;
			followLog = true;
		}
	}

	private void saveIfDirty() {
		if (dirty && !selected.isEmpty()) {
			act(NetlinkPackets.SCRIPT_SAVE);
		}
	}

	private void select(String id) {
		if (id.equals(selected)) {
			return;
		}
		saveIfDirty();
		selected = id;
		loadedCode = false;
		logScroll = 0;
		followLog = true;
		ClientPlayNetworking.send(new NetlinkPackets.ScriptRequest(owner, selected, true));
	}

	private void update(NetlinkPackets.ScriptState s) {
		ownerName = s.ownerName();
		scripts = s.scripts();
		if (!s.selected().equals(selected)) {
			if (s.code().isEmpty() && !selected.isEmpty()) {
				return; // stale answer for another script
			}
			selected = s.selected();
		}
		log = s.log();
		if (selected.isEmpty() && !scripts.isEmpty()) {
			select(scripts.getFirst().id());
			return;
		}
		if (s.code().isPresent() && (!dirty || !loadedCode)) {
			editor.setText(s.code().get());
			dirty = false;
			loadedCode = true;
			for (ScriptInfo i : scripts) {
				if (i.id().equals(selected)) {
					nameBox.setValue(i.name());
				}
			}
			dirty = false;
		}
		// mark the line of the latest error
		int err = -1;
		for (String l : log) {
			if (l.startsWith("E")) {
				Matcher m = ERROR_LINE.matcher(l);
				if (m.find()) {
					err = Integer.parseInt(m.group(1));
				}
			} else if (l.startsWith("S") && l.contains("▶")) {
				err = -1;
			}
		}
		if (!dirty) {
			editor.setErrorLine(err);
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (++refresh >= 10) {
			refresh = 0;
			ClientPlayNetworking.send(new NetlinkPackets.ScriptRequest(owner, selected, !loadedCode));
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.hasControlDownWithQuirk() && event.key() == 83) { // Ctrl+S
			act(NetlinkPackets.SCRIPT_SAVE);
			return true;
		}
		if (event.hasControlDownWithQuirk() && (event.key() == 257 || event.key() == 335)) { // Ctrl+Enter
			act(NetlinkPackets.SCRIPT_RUN);
			return true;
		}
		if (editor.isFocused() && (event.key() == 258 || event.key() == 256 && editor.popupOpen())) {
			return editor.keyPressed(event);
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		saveIfDirty();
		open = null;
		super.onClose();
	}

	@Override
	public void removed() {
		super.removed();
		if (open == this) {
			open = null;
		}
	}

	// ---------------------------------------------------------------- layout helpers

	private int listY() {
		return top + 40;
	}

	private int listH() {
		return top + panelHeight - 10 - 22 - listY();
	}

	private int consoleX() {
		return left + 10 + LIST_W + 8;
	}

	private int consoleH() {
		return Math.max(60, panelHeight / 4);
	}

	private int consoleY() {
		return top + panelHeight - 10 - consoleH();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (editor.popupClick(event.x(), event.y())) {
			return true;
		}
		int lx = left + 10;
		if (event.x() >= lx && event.x() < lx + LIST_W && event.y() >= listY() && event.y() < listY() + listH()) {
			int idx = (int) ((event.y() - listY() - 2) / ROW_H) + listScroll;
			if (idx >= 0 && idx < scripts.size()) {
				select(scripts.get(idx).id());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		int lx = left + 10;
		if (mx >= lx && mx < lx + LIST_W && my >= listY() && my < listY() + listH()) {
			listScroll = Math.max(0, Math.min(Math.max(0, scripts.size() - listH() / ROW_H), listScroll - (int) sy));
			return true;
		}
		if (mx >= consoleX() && my >= consoleY() && my < consoleY() + consoleH()) {
			int visible = (consoleH() - 6) / 9;
			int max = Math.max(0, log.size() - visible);
			logScroll = Math.max(0, Math.min(max, logScroll + (int) sy * 2));
			followLog = logScroll == 0;
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		inset(g, left + 10, listY(), LIST_W, listH());
		inset(g, consoleX() + 150 - 150, top + 26 - 1, 150, 18);
		inset(g, consoleX(), consoleY(), left + panelWidth - 10 - consoleX(), consoleH());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int lx = left + 10;
		Component net = Component.translatable("gui.createnetlink.scripts.network", ownerName);
		g.text(font, net, left + panelWidth - 10 - font.width(net), top + 8, TEXT_DIM, false);
		g.text(font, Component.translatable("gui.createnetlink.scripts.list"), lx, top + 29, TEXT_DIM, false);

		// script list
		g.enableScissor(lx + 1, listY() + 1, lx + LIST_W - 1, listY() + listH() - 1);
		for (int i = listScroll; i < scripts.size(); i++) {
			int ry = listY() + 2 + (i - listScroll) * ROW_H;
			if (ry > listY() + listH()) {
				break;
			}
			ScriptInfo s = scripts.get(i);
			boolean sel = s.id().equals(selected);
			boolean hover = mouseX >= lx && mouseX < lx + LIST_W && mouseY >= ry && mouseY < ry + ROW_H - 1;
			g.fill(lx + 2, ry, lx + LIST_W - 2, ry + ROW_H - 2, sel ? 0xFF1F4466 : hover ? 0xFF1A2533 : 0xFF121924);
			int dot = s.running() ? (s.crashed() ? 0xFFFFB347 : 0xFF3DF5A8) : s.crashed() ? 0xFFFF5370 : 0xFF4F5A67;
			g.fill(lx + 6, ry + 6, lx + 10, ry + 10, dot);
			String label = trim(font, s.name() + (sel && dirty ? " ●" : ""), LIST_W - 22);
			g.text(font, label, lx + 14, ry + 4, sel ? TEXT : 0xFFB8C7D9, false);
		}
		if (scripts.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("gui.createnetlink.scripts.empty"), lx + 6, listY() + 6, LIST_W - 12, TEXT_DIM);
		}
		g.disableScissor();

		// console
		int cx = consoleX(), cy = consoleY(), cw = left + panelWidth - 10 - cx, ch = consoleH();
		g.text(font, Component.translatable("gui.createnetlink.scripts.console"), cx, cy - 12, TEXT_DIM, false);
		int visible = (ch - 6) / 9;
		if (followLog) {
			logScroll = 0;
		}
		int end = Math.max(0, log.size() - logScroll);
		int start = Math.max(0, end - visible);
		g.enableScissor(cx + 1, cy + 1, cx + cw - 1, cy + ch - 1);
		for (int i = start; i < end; i++) {
			String l = log.get(i);
			char level = l.isEmpty() ? 'I' : l.charAt(0);
			int color = switch (level) {
				case 'E' -> 0xFFFF6B7D;
				case 'W' -> 0xFFFFCB6B;
				case 'S' -> 0xFF6FE6FF;
				default -> 0xFFD7E3F0;
			};
			g.text(font, l.length() > 1 ? l.substring(1) : "", cx + 4, cy + 3 + (i - start) * 9, color, false);
		}
		g.disableScissor();
		if (log.isEmpty()) {
			g.text(font, Component.translatable("gui.createnetlink.scripts.console_empty"), cx + 4, cy + 3, 0xFF4F5A67, false);
		}

		// status of the selected script
		for (ScriptInfo s : scripts) {
			if (s.id().equals(selected)) {
				Component st = Component.translatable(s.running() ? "gui.createnetlink.scripts.running" : "gui.createnetlink.scripts.stopped");
				int bx = consoleX() + 156 + 192 + 8;
				g.text(font, st, bx, top + 31, s.running() ? 0xFF3DF5A8 : 0xFF8FA6BF, false);
			}
		}
		Component hint = Component.translatable("gui.createnetlink.scripts.hint");
		g.text(font, hint, cx + font.width(Component.translatable("gui.createnetlink.scripts.console")) + 12, cy - 12, 0xFF4F5A67, false);
		editor.renderOverlay(g);
	}

	public static void openFor(UUID owner, String name) {
		Minecraft.getInstance().setScreen(new ScriptScreen(owner, name));
	}
}
