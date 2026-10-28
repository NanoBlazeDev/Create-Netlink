package dev.exavolt.netlink.client.render;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.exavolt.netlink.Netlink;
import dev.exavolt.netlink.client.ClientState;
import dev.exavolt.netlink.net.NetlinkPackets.HudDevice;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Draws network devices through walls while HUD vision is on. */
public final class XrayRenderer {
	private XrayRenderer() {
	}

	/** Textured (with a plain white texture) so shader packs can draw it unlit through their beacon-beam program. */
	public static final RenderPipeline XRAY_QUADS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
		.withLocation(Netlink.id("pipeline/xray_quads"))
		.withCull(false)
		.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
		.build());
	public static final RenderPipeline XRAY_LINES = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
		.withLocation(Netlink.id("pipeline/xray_lines"))
		.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
		.build());

	private static final RenderType QUADS = RenderType.create("createnetlink_xray_quads",
		RenderSetup.builder(XRAY_QUADS).withTexture("Sampler0", Netlink.id("textures/misc/xray_white.png")).sortOnUpload().createRenderSetup());
	private static final RenderType LINES = RenderType.create("createnetlink_xray_lines",
		RenderSetup.builder(XRAY_LINES).createRenderSetup());

	public static final int COLOR_POST = 0x22B6DC;
	public static final int COLOR_LISTEN = 0x3DF5A8;
	public static final int COLOR_BUG = 0xC77DFF;
	public static final int COLOR_FOREIGN = 0xFF4D4D;
	public static final int COLOR_IDLE = 0x7A8BA3;
	public static final int COLOR_EXTENDER = 0xFFA040;
	public static final int COLOR_ELEVATOR = 0x7DB8FF;

	public static void init() {
		// forces class loading so pipelines register during client init
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
			assignIris();
		}
	}

	/**
	 * Iris replaces every pipeline with a shader-pack program and skips (or garbles) the ones it doesn't know. The quads
	 * go to the beacon-beam program (unlit, glowing), the outlines to the line program. Reflection keeps Iris optional.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void assignIris() {
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Class<? extends Enum> program = (Class<? extends Enum>) Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
			Object iris = api.getMethod("getInstance").invoke(null);
			java.lang.reflect.Method assign = api.getMethod("assignPipeline", RenderPipeline.class, program);
			assign.invoke(iris, XRAY_QUADS, Enum.valueOf(program, "BEACON_BEAM"));
			assign.invoke(iris, XRAY_LINES, Enum.valueOf(program, "LINES"));
		} catch (Throwable t) {
			com.mojang.logging.LogUtils.getLogger().warn("Netlink: couldn't register x-ray pipelines with Iris", t);
		}
	}

	public static int colorOf(HudDevice d) {
		if (d.foreign()) {
			return COLOR_FOREIGN;
		}
		if (d.channel().isEmpty()) {
			return COLOR_IDLE;
		}
		if (d.kind() == 1) {
			return COLOR_BUG;
		}
		if (d.kind() == 2) {
			return COLOR_EXTENDER;
		}
		if (d.kind() == 3) {
			return COLOR_ELEVATOR;
		}
		return d.listen() ? COLOR_LISTEN : COLOR_POST;
	}

	public static void render(LevelRenderContext ctx) {
		if (!ClientState.hudOn || ClientState.devices.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack poseStack = ctx.poseStack();
		MultiBufferSource.BufferSource buffers = ctx.bufferSource();
		float time = (System.currentTimeMillis() % 100000L) / 1000f;
		float pulse = 0.5f + 0.5f * (float) Math.sin(time * 4.0);

		poseStack.pushPose();
		poseStack.translate(-cam.x, -cam.y, -cam.z);
		PoseStack.Pose pose = poseStack.last();

		VertexConsumer quads = buffers.getBuffer(QUADS);
		for (HudDevice d : ClientState.devices) {
			int rgb = colorOf(d);
			int alpha = (int) (40 + (d.value() > 0 ? 50 : 0) + 30 * pulse);
			if (d.kind() == 2) {
				for (BlockPos p : ClientState.platesOf(d)) {
					quadBox(quads, pose, new AABB(p).inflate(0.01), ((alpha / 2) << 24) | rgb);
				}
				quadBox(quads, pose, extenderBug(d), (Math.min(255, alpha + 60) << 24) | COLOR_BUG);
				continue;
			}
			AABB box = boxFor(mc, d);
			quadBox(quads, pose, box, (alpha << 24) | rgb);
		}
		buffers.endBatch(QUADS);

		VertexConsumer lines = buffers.getBuffer(LINES);
		for (HudDevice d : ClientState.devices) {
			int rgb = colorOf(d);
			if (d.kind() == 2) {
				for (BlockPos p : ClientState.platesOf(d)) {
					lineBox(lines, pose, new AABB(p).inflate(0.01), 0xFF000000 | rgb, d.value() > 0 ? 2.5f : 1.5f);
				}
				lineBox(lines, pose, extenderBug(d), 0xFF000000 | COLOR_BUG, 2.0f);
				continue;
			}
			AABB box = boxFor(mc, d);
			lineBox(lines, pose, box, 0xFF000000 | rgb, d.value() > 0 ? 3.0f : 2.0f);
			if (d.kind() == 1) {
				AABB host = hostBox(mc, d.pos());
				lineBox(lines, pose, host.inflate(0.004), ((int) (90 + 60 * pulse) << 24) | rgb, 1.25f);
			}
		}
		buffers.endBatch(LINES);
		poseStack.popPose();
	}

	/** The bug built into a Wall Extender: a small plate on the master's slot face. */
	private static AABB extenderBug(HudDevice d) {
		Direction f = d.face();
		Vec3 c = Vec3.atCenterOf(d.pos()).add(f.getStepX() * 0.5, f.getStepY() * 0.5, f.getStepZ() * 0.5);
		double half = 3 / 16.0, t = 1.0 / 16.0;
		double hx = f.getStepX() != 0 ? t : half, hy = f.getStepY() != 0 ? t : half, hz = f.getStepZ() != 0 ? t : half;
		return new AABB(c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz);
	}

	private static AABB hostBox(Minecraft mc, BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		VoxelShape shape = state.getShape(mc.level, pos);
		AABB b = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
		return b.move(pos);
	}

	/** Links use their real outline; bugs are a small plate sitting on the face they were attached to. */
	private static AABB boxFor(Minecraft mc, HudDevice d) {
		AABB host = hostBox(mc, d.pos());
		if (d.kind() != 1) {
			return host.inflate(0.01);
		}
		Direction f = d.face();
		Vec3 c = host.getCenter();
		double half = 3 / 16.0;
		double t = 1.5 / 16.0;
		double fx = f.getStepX(), fy = f.getStepY(), fz = f.getStepZ();
		double px = fx > 0 ? host.maxX : fx < 0 ? host.minX : c.x;
		double py = fy > 0 ? host.maxY : fy < 0 ? host.minY : c.y;
		double pz = fz > 0 ? host.maxZ : fz < 0 ? host.minZ : c.z;
		double hx = fx != 0 ? t / 2 : half;
		double hy = fy != 0 ? t / 2 : half;
		double hz = fz != 0 ? t / 2 : half;
		px += fx * t / 2;
		py += fy * t / 2;
		pz += fz * t / 2;
		return new AABB(px - hx, py - hy, pz - hz, px + hx, py + hy, pz + hz);
	}

	private static void quad(VertexConsumer vc, PoseStack.Pose pose, int color,
	                         double x1, double y1, double z1, double x2, double y2, double z2,
	                         double x3, double y3, double z3, double x4, double y4, double z4) {
		vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setUv(0.5f, 0.5f).setColor(color);
		vc.addVertex(pose, (float) x2, (float) y2, (float) z2).setUv(0.5f, 0.5f).setColor(color);
		vc.addVertex(pose, (float) x3, (float) y3, (float) z3).setUv(0.5f, 0.5f).setColor(color);
		vc.addVertex(pose, (float) x4, (float) y4, (float) z4).setUv(0.5f, 0.5f).setColor(color);
	}

	private static void quadBox(VertexConsumer vc, PoseStack.Pose pose, AABB b, int color) {
		double x0 = b.minX, y0 = b.minY, z0 = b.minZ, x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
		quad(vc, pose, color, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		quad(vc, pose, color, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
		quad(vc, pose, color, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
		quad(vc, pose, color, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		quad(vc, pose, color, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		quad(vc, pose, color, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
	}

	private static void line(VertexConsumer vc, PoseStack.Pose pose, int color, float width,
	                         double x1, double y1, double z1, double x2, double y2, double z2) {
		float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6f) {
			return;
		}
		dx /= len;
		dy /= len;
		dz /= len;
		vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(width);
		vc.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(width);
	}

	private static void lineBox(VertexConsumer vc, PoseStack.Pose pose, AABB b, int color, float width) {
		double x0 = b.minX, y0 = b.minY, z0 = b.minZ, x1 = b.maxX, y1 = b.maxY, z1 = b.maxZ;
		line(vc, pose, color, width, x0, y0, z0, x1, y0, z0);
		line(vc, pose, color, width, x0, y0, z1, x1, y0, z1);
		line(vc, pose, color, width, x0, y1, z0, x1, y1, z0);
		line(vc, pose, color, width, x0, y1, z1, x1, y1, z1);
		line(vc, pose, color, width, x0, y0, z0, x0, y1, z0);
		line(vc, pose, color, width, x1, y0, z0, x1, y1, z0);
		line(vc, pose, color, width, x0, y0, z1, x0, y1, z1);
		line(vc, pose, color, width, x1, y0, z1, x1, y1, z1);
		line(vc, pose, color, width, x0, y0, z0, x0, y0, z1);
		line(vc, pose, color, width, x1, y0, z0, x1, y0, z1);
		line(vc, pose, color, width, x0, y1, z0, x0, y1, z1);
		line(vc, pose, color, width, x1, y1, z0, x1, y1, z1);
	}
}
