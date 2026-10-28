package dev.exavolt.netlinkdecor.client.screen;

import dev.exavolt.netlink.client.screen.NetlinkScreen;
import dev.exavolt.netlinkdecor.item.ChiselSettings;
import dev.exavolt.netlinkdecor.net.DecorPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Voxel Chisel settings: resolution factor, brush size and shape, and what right-click does. */
public class ChiselScreen extends NetlinkScreen {
	private static final int[] RESOLUTIONS = {1, 2, 4, 8, 16, 32, 64};
	private int res;
	private int brush;
	private int shape;
	private int mode;
	private final ChiselSettings start;

	public ChiselScreen(ChiselSettings s) {
		super(Component.translatable("gui.netlinkdecor.chisel.title"), 252, 150);
		this.start = s;
		this.res = s.res();
		this.brush = s.brush();
		this.shape = s.shape();
		this.mode = s.mode();
	}

	@Override
	protected void init() {
		super.init();
		int x = left + 10;
		int y = top + 38;
		for (int i = 0; i < RESOLUTIONS.length; i++) {
			int r = RESOLUTIONS[i];
			NetButton b = button(x + i * 33, y, 31, 16, Component.literal(String.valueOf(r)), btn -> res = r);
			b.selected = () -> res == r;
		}
		y += 34;
		button(x, y, 20, 16, Component.literal("-"), b -> brush = Math.max(1, brush - 1));
		button(x + 62, y, 20, 16, Component.literal("+"), b -> brush = Math.min(ChiselSettings.MAX_BRUSH, brush + 1));
		NetButton cube = button(x + 100, y, 60, 16, Component.translatable("gui.netlinkdecor.chisel.cube"), b -> shape = ChiselSettings.CUBE);
		cube.selected = () -> shape == ChiselSettings.CUBE;
		NetButton sphere = button(x + 164, y, 60, 16, Component.translatable("gui.netlinkdecor.chisel.sphere"), b -> shape = ChiselSettings.SPHERE);
		sphere.selected = () -> shape == ChiselSettings.SPHERE;
		y += 34;
		NetButton add = button(x, y, 60, 16, Component.translatable("gui.netlinkdecor.chisel.add"), b -> mode = ChiselSettings.ADD);
		add.selected = () -> mode == ChiselSettings.ADD;
		NetButton paint = button(x + 64, y, 60, 16, Component.translatable("gui.netlinkdecor.chisel.paint"), b -> mode = ChiselSettings.PAINT);
		paint.selected = () -> mode == ChiselSettings.PAINT;
		button(left + panelWidth - 10 - 70, y, 70, 16, Component.translatable("gui.netlinkdecor.done"), b -> onClose());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int x = left + 10;
		g.text(font, Component.translatable("gui.netlinkdecor.chisel.resolution", res, res * res * res), x, top + 27, TEXT_DIM, false);
		g.text(font, Component.translatable("gui.netlinkdecor.chisel.brush"), x, top + 61, TEXT_DIM, false);
		g.centeredText(font, Component.literal(String.valueOf(brush)), x + 41, top + 76, TEXT);
		g.text(font, Component.translatable("gui.netlinkdecor.chisel.right_click"), x, top + 95, TEXT_DIM, false);
		g.text(font, Component.translatable("gui.netlinkdecor.chisel.hint"), x, top + panelHeight - 12, 0xFF5F7085, false);
	}

	@Override
	public void onClose() {
		ChiselSettings next = new ChiselSettings(res, brush, shape, mode, start.material());
		if (!next.equals(start)) {
			ClientPlayNetworking.send(new DecorPackets.ChiselConfigure(next));
		}
		super.onClose();
	}
}
