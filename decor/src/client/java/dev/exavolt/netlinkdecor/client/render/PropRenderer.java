package dev.exavolt.netlinkdecor.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zurrtum.create.client.catnip.render.CachedBuffers;
import com.zurrtum.create.client.catnip.render.SuperByteBuffer;
import com.zurrtum.create.client.catnip.render.SuperByteBufferRenderState;
import com.zurrtum.create.client.flywheel.lib.model.baked.PartialModel;
import dev.exavolt.netlinkdecor.DecorRegistry;
import dev.exavolt.netlinkdecor.NetlinkDecor;
import dev.exavolt.netlinkdecor.block.AntennaMastBlock;
import dev.exavolt.netlinkdecor.block.LiveBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings the wireless props to life: blinking drive and port LEDs, a turning satellite dish, the mast's aviation light
 * and the status monitor's readout. Everything follows the linked channel; unlinked props just idle.
 */
public class PropRenderer implements BlockEntityRenderer<LiveBlockEntity, PropRenderer.State> {
	public static PartialModel DISH;

	public static void init() {
		DISH = PartialModel.of(NetlinkDecor.id("block/satellite_dish_dish"));
	}

	private static final int GREEN = 0xFF39F07A;
	private static final int AMBER = 0xFFFFB13B;
	private static final int CYAN = 0xFF4FE3FF;
	private static final int RED = 0xFFFF3B30;
	private static final int OFF = 0xFF1B2A22;

	/** One glowing rectangle in block pixels on a plane facing north (z = depth in pixels). */
	record Led(float x0, float y0, float x1, float y1, float z, int color) {
	}

	public static class State extends BlockEntityRenderState {
		float yRot;
		final List<Led> leds = new ArrayList<>();
		/** Small glowing cube (mast light): x, y, z, size in pixels; color 0 = none. */
		float[] beacon;
		int beaconColor;
		@Nullable SuperByteBufferRenderState dish;
		@Nullable Component big;
		@Nullable Component small;
		int bigColor;
		int value;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	private static float hash(long seed) {
		seed = (seed ^ 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
		seed ^= seed >>> 31;
		return (seed & 0xFFFFFF) / (float) 0xFFFFFF;
	}

	/** Whether a pseudo-random LED is lit right now; busier = more often. */
	private static boolean blink(long seed, double time, double rate, double duty) {
		long slot = (long) Math.floor(time * rate + hash(seed) * 7);
		return hash(seed * 31 + slot) < duty;
	}

	@Override
	public void extractRenderState(LiveBlockEntity be, State state, float partialTicks, Vec3 cameraPosition,
	                               ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, cameraPosition, breakProgress);
		BlockState bs = be.getBlockState();
		Direction facing = bs.hasProperty(HorizontalDirectionalBlock.FACING) ? bs.getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
		state.yRot = switch (facing) {
			case EAST -> 90;
			case SOUTH -> 180;
			case WEST -> 270;
			default -> 0;
		};
		state.leds.clear();
		state.beacon = null;
		state.dish = null;
		state.big = null;
		state.small = null;
		int v = be.value();
		state.value = v;
		boolean linked = be.linked();
		long seed = be.getBlockPos().asLong();
		double time = (be.getLevel() == null ? 0 : be.getLevel().getGameTime()) + partialTicks;
		double busy = !linked ? 0.35 : v <= 0 ? 0.08 : 0.35 + 0.6 * v / 15.0;
		double rate = !linked ? 0.15 : v <= 0 ? 0.05 : 0.25 + 0.5 * v / 15.0;

		if (bs.is(DecorRegistry.SERVER_RACK)) {
			// 6 drive bays: activity LED + health LED each, and the status light on top
			for (int i = 0; i < 6; i++) {
				float y0 = 12.6F - i * 2F;
				boolean act = blink(seed + i, time, rate * 3, busy);
				state.leds.add(new Led(12.2F, y0, 13.0F, y0 + 0.6F, -0.02F, act ? GREEN : OFF));
				state.leds.add(new Led(13.4F, y0, 14.2F, y0 + 0.6F, -0.02F, linked && v > 0 && blink(seed * 7 + i, time, 0.1, 0.15) ? AMBER : 0xFF16331F));
			}
			boolean beat = (time / 20.0) % 1 < 0.5;
			state.leds.add(new Led(2.0F, 14.4F, 3.0F, 15.0F, -0.02F, !linked ? (beat ? CYAN : 0xFF0E3440) : v > 0 ? GREEN : AMBER));
		} else if (bs.is(DecorRegistry.NETWORK_SWITCH)) {
			for (int i = 0; i < 8; i++) {
				float x0 = 2.6F + i * 1.4F;
				state.leds.add(new Led(x0, 2.9F, x0 + 0.6F, 3.4F, 1.98F, blink(seed + i, time, rate * 4, busy) ? GREEN : OFF));
			}
			state.leds.add(new Led(13.4F, 1.2F, 14.0F, 1.8F, 1.98F, linked ? (v > 0 ? CYAN : AMBER) : GREEN));
		} else if (bs.is(DecorRegistry.ROUTER)) {
			state.leds.add(new Led(5.0F, 1.2F, 5.8F, 1.8F, 3.98F, GREEN));
			state.leds.add(new Led(7.6F, 1.2F, 8.4F, 1.8F, 3.98F, blink(seed, time, rate * 4, busy) ? CYAN : OFF));
			state.leds.add(new Led(10.2F, 1.2F, 11.0F, 1.8F, 3.98F, linked ? (v > 0 ? GREEN : AMBER) : OFF));
		} else if (bs.is(DecorRegistry.PATCH_PANEL)) {
			int lit = linked ? Math.round(12 * Math.max(0, v) / 15F) : -1;
			for (int i = 0; i < 12; i++) {
				int row = i / 6, col = i % 6;
				float x0 = 2.3F + col * 2.0F;
				float y0 = 10.4F - row * 5.0F;
				boolean on = lit < 0 ? blink(seed + i, time, 0.2, 0.5) : i < lit && blink(seed + i, time, rate * 3, 0.85);
				state.leds.add(new Led(x0, y0, x0 + 0.6F, y0 + 0.6F, 12.98F, on ? GREEN : OFF));
			}
		} else if (bs.is(DecorRegistry.ANTENNA_MAST)) {
			if (bs.getValue(AntennaMastBlock.TOP)) {
				boolean on = linked && v > 0 ? (time / 4) % 1 < 0.35 : (time / 30) % 1 < 0.45;
				state.beacon = new float[]{7, 15, 7, 2};
				state.beaconColor = on ? (linked && v > 0 ? 0xFFFFFFFF : RED) : 0;
			}
		} else if (bs.is(DecorRegistry.SATELLITE_DISH)) {
			double speed = !linked ? 0.4 : v <= 0 ? 0 : 0.4 + v * 0.35;
			float angle = (float) ((time * speed + hash(seed) * 360) % 360);
			SuperByteBuffer buf = CachedBuffers.partial(DISH, bs);
			buf.center().rotateYDegrees(-state.yRot + angle).uncenter();
			state.dish = buf.cardinalLighting(be.getLevel()).light(state.lightCoords).extractRenderState();
		} else if (bs.is(DecorRegistry.STATUS_MONITOR)) {
			if (!linked) {
				state.big = Component.literal("NO LINK");
				state.bigColor = 0xFF2C6F86;
			} else {
				state.big = Component.literal(String.valueOf(Math.max(0, v)));
				state.bigColor = v > 0 ? 0xFF3DF5A8 : 0xFFFFCB6B;
				String ch = be.channel();
				state.small = Component.literal(ch.length() > 18 ? "…" + ch.substring(ch.length() - 17) : ch);
				float w = 10F * Math.max(0, v) / 15F;
				state.leds.add(new Led(3F, 5.0F, 13F, 5.6F, 5.97F, 0xFF0E2A33));
				if (w > 0) {
					state.leds.add(new Led(3F, 5.0F, 3F + w, 5.6F, 5.96F, v > 0 ? 0xFF3DF5A8 : AMBER));
				}
			}
		}
	}

	private static void quad(VertexConsumer vc, PoseStack.Pose pose, float x0, float y0, float x1, float y1, float z, int color) {
		float a = x0 / 16F, b = x1 / 16F, c = y0 / 16F, d = y1 / 16F, zz = z / 16F;
		int light = LightCoordsUtil.FULL_BRIGHT;
		// both windings: visible whatever the pipeline culls
		vc.addVertex(pose, b, c, zz).setColor(color).setLight(light);
		vc.addVertex(pose, a, c, zz).setColor(color).setLight(light);
		vc.addVertex(pose, a, d, zz).setColor(color).setLight(light);
		vc.addVertex(pose, b, d, zz).setColor(color).setLight(light);
		vc.addVertex(pose, b, d, zz).setColor(color).setLight(light);
		vc.addVertex(pose, a, d, zz).setColor(color).setLight(light);
		vc.addVertex(pose, a, c, zz).setColor(color).setLight(light);
		vc.addVertex(pose, b, c, zz).setColor(color).setLight(light);
	}

	private static void cube(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float s, int color) {
		float x0 = x / 16F, y0 = y / 16F, z0 = z / 16F, x1 = (x + s) / 16F, y1 = (y + s) / 16F, z1 = (z + s) / 16F;
		int l = LightCoordsUtil.FULL_BRIGHT;
		float[][] faces = {
			{x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0},
			{x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1},
			{x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1},
			{x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0},
			{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1},
			{x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0},
		};
		for (float[] f : faces) {
			for (int i = 0; i < 4; i++) {
				vc.addVertex(pose, f[i * 3], f[i * 3 + 1], f[i * 3 + 2]).setColor(color).setLight(l);
			}
			for (int i = 3; i >= 0; i--) {
				vc.addVertex(pose, f[i * 3], f[i * 3 + 1], f[i * 3 + 2]).setColor(color).setLight(l);
			}
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.dish != null) {
			state.dish.submit(poseStack, collector);
		}
		poseStack.pushPose();
		poseStack.translate(0.5F, 0, 0.5F);
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
		poseStack.translate(-0.5F, 0, -0.5F);
		if (!state.leds.isEmpty() || state.beacon != null && state.beaconColor != 0) {
			List<Led> leds = List.copyOf(state.leds);
			float[] beacon = state.beaconColor != 0 ? state.beacon : null;
			int beaconColor = state.beaconColor;
			collector.submitCustomGeometry(poseStack, RenderTypes.textBackground(), (pose, vc) -> {
				for (Led l : leds) {
					quad(vc, pose, l.x0(), l.y0(), l.x1(), l.y1(), l.z(), l.color());
				}
				if (beacon != null) {
					cube(vc, pose, beacon[0], beacon[1], beacon[2], beacon[3], beaconColor);
				}
			});
		}
		if (state.big != null) {
			Font font = Minecraft.getInstance().font;
			// screen glass: x 2..14, y 4..12, front at z 6 (model faces north)
			poseStack.translate(8 / 16F, 9.2F / 16F, 5.95F / 16F);
			poseStack.mulPose(Axis.YP.rotationDegrees(180));
			float scale = 0.02F;
			int bw = font.width(state.big);
			if (bw * scale > 10F / 16F) {
				scale = 10F / 16F / bw;
			}
			poseStack.pushPose();
			poseStack.scale(scale, -scale, scale);
			collector.submitText(poseStack, -bw / 2F, -4F, state.big.getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET,
				LightCoordsUtil.FULL_BRIGHT, state.bigColor, 0, 0);
			poseStack.popPose();
			if (state.small != null) {
				float s = 0.0065F;
				int sw = font.width(state.small);
				if (sw * s > 10F / 16F) {
					s = 10F / 16F / sw;
				}
				poseStack.pushPose();
				poseStack.translate(0, 2.2F / 16F, 0);
				poseStack.scale(s, -s, s);
				collector.submitText(poseStack, -sw / 2F, -4F, state.small.getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET,
					LightCoordsUtil.FULL_BRIGHT, 0xFF22B6DC, 0, 0);
				poseStack.popPose();
			}
		}
		poseStack.popPose();
	}

	@Override
	public int getViewDistance() {
		return 64;
	}
}
