package dev.exavolt.netlink.client.screen;

import dev.exavolt.netlink.Netlink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shared look for every Netlink screen: dark steel panels, cyan accents, brass rivets. */
public abstract class NetlinkScreen extends Screen {
	public static final Identifier PANEL = Netlink.id("panel");
	public static final Identifier INSET = Netlink.id("inset");
	public static final Identifier BUTTON = Netlink.id("button_normal");
	public static final Identifier BUTTON_HOVER = Netlink.id("button_hover");
	public static final Identifier BUTTON_OFF = Netlink.id("button_off");

	public static final int TEXT = 0xFFE6F6FF;
	public static final int TEXT_DIM = 0xFF8FA6BF;
	public static final int ACCENT = 0xFF6FE6FF;
	public static final int ACCENT_DARK = 0xFF22B6DC;
	public static final int WARN = 0xFFFF8080;

	protected int left;
	protected int top;
	protected int panelWidth;
	protected int panelHeight;

	protected NetlinkScreen(Component title, int panelWidth, int panelHeight) {
		super(title);
		this.panelWidth = panelWidth;
		this.panelHeight = panelHeight;
	}

	@Override
	protected void init() {
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		panel(g, left, top, panelWidth, panelHeight);
		g.text(font, getTitle(), left + 10, top + 8, ACCENT, false);
		g.fill(left + 8, top + 19, left + panelWidth - 8, top + 20, 0xFF2A3341);
	}

	// ------------------------------------------------------------------ drawing helpers

	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x, y, w, h);
	}

	public static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, INSET, x, y, w, h);
	}

	/** 15-segment signal meter. */
	public static void signalBar(GuiGraphicsExtractor g, int x, int y, int w, int h, int value, int color) {
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF05080D);
		float seg = w / 15f;
		for (int k = 0; k < 15; k++) {
			int sx = x + Math.round(k * seg);
			int ex = x + Math.round((k + 1) * seg) - 1;
			g.fill(sx, y, Math.max(sx + 1, ex), y + h, k < value ? color : 0xFF1F2939);
		}
	}

	protected EditBox field(int x, int y, int w, String value) {
		EditBox box = new EditBox(font, x + 4, y + 4, w - 8, 12, Component.empty());
		box.setBordered(false);
		box.setMaxLength(64);
		box.setValue(value);
		box.setTextColor(TEXT);
		addRenderableWidget(box);
		return box;
	}

	protected NetButton button(int x, int y, int w, int h, Component label, Consumer<NetButton> action) {
		NetButton b = new NetButton(x, y, w, h, label, action);
		addRenderableWidget(b);
		return b;
	}

	protected static String trim(Font font, String s, int width) {
		if (font.width(s) <= width) {
			return s;
		}
		String dots = "…";
		while (!s.isEmpty() && font.width(s + dots) > width) {
			s = s.substring(0, s.length() - 1);
		}
		return s + dots;
	}

	/** Flat hi-tech button. {@code selected} keeps it lit like a toggle. */
	public static class NetButton extends AbstractButton {
		private final Consumer<NetButton> action;
		public BooleanSupplier selected = () -> false;

		public NetButton(int x, int y, int w, int h, Component label, Consumer<NetButton> action) {
			super(x, y, w, h, label);
			this.action = action;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			action.accept(this);
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			boolean sel = selected.getAsBoolean();
			Identifier sprite = !active ? BUTTON_OFF : (isHoveredOrFocused() || sel) ? BUTTON_HOVER : BUTTON;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
			Font font = Minecraft.getInstance().font;
			int color = !active ? 0xFF4F5A67 : sel ? 0xFF0B0F16 : isHoveredOrFocused() ? 0xFFFFFFFF : TEXT;
			g.centeredText(font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
