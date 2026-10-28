package dev.exavolt.netlink.client.screen;

import com.zurrtum.create.client.AllSpecialTextures;
import com.zurrtum.create.client.catnip.outliner.Outliner;
import dev.exavolt.netlink.client.render.ExtenderPreviewState;
import dev.exavolt.netlink.net.NetlinkPackets;
import dev.exavolt.netlink.network.WallExtenders;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pick which side of a glued structure the Wall Extender plates go on. A live 3D preview shows the plates and the wall
 * sliding out of them and back, so it's obvious how the extender will move; drag to orbit, scroll to zoom.
 */
public class ExtenderAssemblyScreen extends NetlinkScreen {
	private static final Object STRUCTURE = new Object();
	private static final Object PLATES = new Object();
	private static final Object MISSING = new Object();
	private static final Direction[] ORDER = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private static final int PREVIEW_W = 196;
	private static final int PREVIEW_H = 168;

	private final BlockPos clicked;
	private final Set<BlockPos> structure;
	private final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
	private Direction side = Direction.DOWN;
	private Set<BlockPos> plates = Set.of();
	private Map<BlockPos, BlockState> plateStates = Map.of();
	private Set<BlockPos> missing = Set.of();
	private NetButton foldButton;
	private int travel;
	private int plane;
	private double cx, cy, cz;
	private double radius = 1;

	private float yaw = 35;
	private float pitch = 28;
	private float zoom = 1;
	private boolean dragging;
	private long lastInteraction;
	private long lastFrame;
	private final long opened = System.currentTimeMillis();

	public ExtenderAssemblyScreen(NetlinkPackets.OpenExtenderAssembly data) {
		super(Component.translatable("gui.createnetlink.extender_assembly.title"), 336, 206);
		this.clicked = data.clicked();
		this.structure = new LinkedHashSet<>(data.structure());
		Minecraft mc = Minecraft.getInstance();
		for (BlockPos p : structure) {
			states.put(p, mc.level != null ? mc.level.getBlockState(p) : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		}
		select(Direction.DOWN);
	}

	private static int dot(BlockPos p, Direction d) {
		return p.getX() * d.getStepX() + p.getY() * d.getStepY() + p.getZ() * d.getStepZ();
	}

	private void select(Direction d) {
		side = d;
		plates = WallExtenders.plateCells(structure, d);
		Minecraft mc = Minecraft.getInstance();
		Map<BlockPos, BlockState> ps = new LinkedHashMap<>();
		Set<BlockPos> miss = new LinkedHashSet<>();
		for (BlockPos p : plates) {
			boolean ok = mc.level != null && !structure.contains(p) && WallExtenders.canHostPlate(mc.level, p);
			ps.put(p, ok ? mc.level.getBlockState(p) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			if (!ok) {
				miss.add(p);
			}
		}
		plateStates = ps;
		missing = miss;
		Direction up = d.getOpposite();
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		for (BlockPos p : structure) {
			min = Math.min(min, dot(p, up));
			max = Math.max(max, dot(p, up));
		}
		plane = min - 1;
		travel = max - plane;

		double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE;
		double x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
		List<BlockPos> all = new ArrayList<>(structure);
		all.addAll(plates);
		for (BlockPos p : all) {
			x0 = Math.min(x0, p.getX());
			y0 = Math.min(y0, p.getY());
			z0 = Math.min(z0, p.getZ());
			x1 = Math.max(x1, p.getX() + 1);
			y1 = Math.max(y1, p.getY() + 1);
			z1 = Math.max(z1, p.getZ() + 1);
		}
		cx = (x0 + x1) / 2;
		cy = (y0 + y1) / 2;
		cz = (z0 + z1) / 2;
		radius = Math.max(1, Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0)) / 2);
	}

	private int px() {
		return left + 10;
	}

	private int py() {
		return top + 26;
	}

	@Override
	protected void init() {
		super.init();
		int x = px() + PREVIEW_W + 10;
		int w = left + panelWidth - 10 - x;
		int bw = (w - 4) / 2;
		int y = top + 40;
		for (int k = 0; k < ORDER.length; k++) {
			Direction d = ORDER[k];
			NetButton b = button(x + (k % 2) * (bw + 4), y + (k / 2) * 20, bw, 16,
				Component.translatable("gui.createnetlink.extender_assembly.side." + d.getSerializedName()), btn -> select(d));
			b.selected = () -> side == d;
		}
		int by = top + panelHeight - 26;
		foldButton = button(x, by, bw, 18, Component.translatable("gui.createnetlink.extender_assembly.apply"), b -> apply());
		button(x + bw + 4, by, bw, 18, Component.translatable("gui.createnetlink.cancel"), b -> onClose());
	}

	private void apply() {
		if (!missing.isEmpty()) {
			return;
		}
		ClientPlayNetworking.send(new NetlinkPackets.AssembleExtender(clicked, side.get3DDataValue()));
		onClose();
	}

	@Override
	public void tick() {
		super.tick();
		Outliner.getInstance().showCluster(STRUCTURE, structure).colored(0xFFA040).disableLineNormals().lineWidth(1 / 32f);
		Outliner.getInstance().showCluster(PLATES, plates).colored(0x6FE6FF)
			.withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
			.disableLineNormals().lineWidth(1 / 16f);
		if (!missing.isEmpty()) {
			Outliner.getInstance().showCluster(MISSING, missing).colored(0xFF4D4D).disableLineNormals().lineWidth(1 / 12f);
		}
		if (foldButton != null) {
			foldButton.active = missing.isEmpty();
		}
		// blocks may have been placed while the screen is open
		if (Minecraft.getInstance().level != null && Minecraft.getInstance().level.getGameTime() % 10 == 0) {
			select(side);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == 257 || event.key() == 335) {
			apply();
			return true;
		}
		return super.keyPressed(event);
	}

	private boolean overPreview(double mx, double my) {
		return mx >= px() && mx < px() + PREVIEW_W && my >= py() && my < py() + PREVIEW_H;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (overPreview(event.x(), event.y())) {
			dragging = true;
			lastInteraction = System.currentTimeMillis();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			yaw += (float) dx * 1.2F;
			pitch = Mth.clamp(pitch + (float) dy * 1.2F, -89, 89);
			lastInteraction = System.currentTimeMillis();
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (overPreview(mouseX, mouseY)) {
			zoom = Mth.clamp(zoom * (scrollY > 0 ? 1.15F : 1 / 1.15F), 0.3F, 6F);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** Sink (blocks still inside the plates) for the looping demo: extend, hold, retract, hold. */
	private float demoSink(float seconds) {
		float move = Math.max(0.8F, travel / 6.0F);
		float hold = 0.7F;
		float period = 2 * move + 2 * hold;
		float t = seconds % period;
		float ext;
		if (t < hold) {
			ext = 0;
		} else if (t < hold + move) {
			ext = smooth((t - hold) / move);
		} else if (t < 2 * hold + move) {
			ext = 1;
		} else {
			ext = 1 - smooth((t - 2 * hold - move) / move);
		}
		return travel * (1 - ext);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0, 1);
		return x * x * (3 - 2 * x);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		inset(g, px() - 2, py() - 2, PREVIEW_W + 4, PREVIEW_H + 4);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		long now = System.currentTimeMillis();
		if (lastFrame != 0 && !dragging && now - lastInteraction > 2500) {
			yaw += (now - lastFrame) * 0.02F;
		}
		lastFrame = now;
		float seconds = (now - opened) / 1000F;
		float scale = (float) (Math.min(PREVIEW_W, PREVIEW_H) / (radius * 2.3)) * zoom;
		int x0 = px(), y0 = py();
		ScreenRectangle area = new ScreenRectangle(x0, y0, PREVIEW_W, PREVIEW_H);
		g.guiRenderState.addPicturesInPictureState(new ExtenderPreviewState(
			states, plateStates, side.getOpposite(), plane, demoSink(seconds), yaw, pitch, cx, cy, cz,
			x0, y0, x0 + PREVIEW_W, y0 + PREVIEW_H, scale, area, area
		));
		g.text(font, Component.translatable("gui.createnetlink.extender_assembly.drag"), x0 + 4, y0 + PREVIEW_H - 11, 0x998FA6BF, false);

		int x = px() + PREVIEW_W + 10;
		g.text(font, Component.translatable("gui.createnetlink.extender_assembly.pick"), x, top + 27, TEXT_DIM, false);
		int y = top + 108;
		g.text(font, Component.translatable("gui.createnetlink.extender_assembly.blocks", structure.size()), x, y, TEXT, false);
		g.text(font, Component.translatable("gui.createnetlink.extender_assembly.plates", plates.size()), x, y + 12, ACCENT, false);
		if (!missing.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("gui.createnetlink.extender_assembly.missing", missing.size()), x, y + 52,
				left + panelWidth - 10 - x, WARN);
		}
		g.text(font, Component.translatable("gui.createnetlink.extender_assembly.travel_short", travel), x, y + 24, TEXT_DIM, false);
		g.text(font, Component.translatable("gui.createnetlink.extender_assembly.dir." + side.getOpposite().getSerializedName()), x, y + 36,
			TEXT_DIM, false);
	}
}
