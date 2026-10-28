package dev.exavolt.netlink.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zurrtum.create.catnip.data.Couple;
import com.zurrtum.create.catnip.data.IntAttached;
import com.zurrtum.create.client.catnip.render.CachedBuffers;
import com.zurrtum.create.client.catnip.render.SuperByteBuffer;
import com.zurrtum.create.client.catnip.render.SuperByteBufferRenderState;
import com.zurrtum.create.client.content.contraptions.render.ClientContraption;
import com.zurrtum.create.client.flywheel.lib.model.baked.PartialModel;
import com.zurrtum.create.content.contraptions.AbstractContraptionEntity;
import com.zurrtum.create.content.contraptions.elevator.ElevatorContraption;
import dev.exavolt.netlink.Netlink;
import dev.exavolt.netlink.block.ElevatorPanelBlock;
import dev.exavolt.netlink.block.ElevatorPanelBlockEntity;
import dev.exavolt.netlink.client.ElevatorPanelClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws the panel's 12 keys (each one is pushed in while its key is pressed on the keyboard) and the screen: the floor
 * being typed, or where the cabin is and where it's going. Works in the world and inside elevator cabins.
 */
public class ElevatorPanelRenderer implements BlockEntityRenderer<ElevatorPanelBlockEntity, ElevatorPanelRenderer.State> {
	public static final PartialModel[] KEYS = new PartialModel[12];
	private static final float TRAVEL = 0.7F / 16F;
	private static final Map<ElevatorPanelBlockEntity, Integer> OWNER = new WeakHashMap<>();

	public static void init() {
		for (int i = 0; i < KEYS.length; i++) {
			KEYS[i] = PartialModel.of(Netlink.id("block/elevator_panel/key_" + i));
		}
	}

	public static class State extends BlockEntityRenderState {
		final SuperByteBufferRenderState[] keys = new SuperByteBufferRenderState[12];
		float yRot;
		@Nullable Component big;
		@Nullable Component small;
		int bigColor;
		int smallColor;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	/** The cabin this block entity belongs to, if it is part of one. */
	private static @Nullable AbstractContraptionEntity cabin(ElevatorPanelBlockEntity be) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || be.getLevel() == mc.level) {
			return null;
		}
		Integer id = OWNER.get(be);
		if (id != null) {
			Entity e = mc.level.getEntity(id);
			if (e instanceof AbstractContraptionEntity ace && ace.isAlive()
				&& ClientContraption.getBlockEntityClientSide(ace.getContraption(), be.getBlockPos()) == be) {
				return ace;
			}
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		for (AbstractContraptionEntity ace : mc.level.getEntitiesOfClass(AbstractContraptionEntity.class, new AABB(cam, cam).inflate(96))) {
			if (ace.getContraption() != null && ClientContraption.getBlockEntityClientSide(ace.getContraption(), be.getBlockPos()) == be) {
				OWNER.put(be, ace.getId());
				return ace;
			}
		}
		return null;
	}

	@Override
	public void extractRenderState(ElevatorPanelBlockEntity be, State state, float partialTicks, Vec3 cameraPosition,
	                               ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, cameraPosition, breakProgress);
		net.minecraft.world.level.block.state.BlockState blockState = be.getBlockState();
		Direction facing = blockState.hasProperty(ElevatorPanelBlock.FACING) ? blockState.getValue(ElevatorPanelBlock.FACING) : Direction.NORTH;
		state.yRot = switch (facing) {
			case EAST -> 90;
			case SOUTH -> 180;
			case WEST -> 270;
			default -> 0;
		};
		AbstractContraptionEntity cabin = cabin(be);
		int entity = cabin == null ? -1 : cabin.getId();
		BlockPos pos = be.getBlockPos();
		Level level = be.getLevel();
		for (int i = 0; i < KEYS.length; i++) {
			float press = entity < 0 ? 0 : ElevatorPanelClient.press(entity, pos, i);
			SuperByteBuffer buf = CachedBuffers.partial(KEYS[i], blockState);
			buf.center().rotateYDegrees(-state.yRot).uncenter().translate(0, 0, press * TRAVEL);
			state.keys[i] = buf.cardinalLighting(level).light(state.lightCoords).extractRenderState();
		}

		// screen
		state.big = null;
		state.small = null;
		if (cabin == null || !(cabin.getContraption() instanceof ElevatorContraption ec)) {
			state.big = Component.literal("--");
			state.bigColor = 0xFF2C6F86;
			return;
		}
		List<IntAttached<Couple<String>>> floors = ec.namesList;
		double y = cabin.getY() + ec.getContactYOffset();
		int target = ec.clientYTarget;
		String here = null, hereLabel = "", going = null;
		for (int i = 0; i < floors.size(); i++) {
			IntAttached<Couple<String>> f = floors.get(i);
			String name = f.getValue().getFirst() == null || f.getValue().getFirst().isBlank() ? String.valueOf(i) : f.getValue().getFirst();
			if (Math.abs(f.getFirst() - y) < 0.5) {
				here = name;
				hereLabel = f.getValue().getSecond() == null ? "" : f.getValue().getSecond();
			}
			if (f.getFirst() == target) {
				going = name;
			}
		}
		boolean moving = here == null || going != null && !going.equals(here);
		if (ElevatorPanelClient.isFocused(entity, pos)) {
			boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
			String typed = ElevatorPanelClient.typed();
			state.big = Component.literal(typed + (blink ? "_" : " "));
			state.bigColor = 0xFFD4FBFF;
			state.small = Component.literal(typed.isEmpty() ? "FLOOR?" : "ENTER ↵");
			state.smallColor = 0xFF22B6DC;
			return;
		}
		String sent = ElevatorPanelClient.recentlySent(entity, pos);
		if (sent != null) {
			state.big = Component.literal("→" + sent);
			state.bigColor = 0xFFFFCB6B;
			return;
		}
		if (moving && going != null) {
			boolean up = target > y;
			state.big = Component.literal((up ? "▲" : "▼") + going);
			state.bigColor = 0xFFFFCB6B;
			state.small = Component.literal(up ? "GOING UP" : "GOING DOWN");
			state.smallColor = 0xFFB08A3A;
		} else {
			state.big = Component.literal(here == null ? "--" : here);
			state.bigColor = 0xFF3DF5A8;
			state.small = hereLabel.isEmpty() ? null : Component.literal(hereLabel.length() > 14 ? hereLabel.substring(0, 14) : hereLabel);
			state.smallColor = 0xFF2C9E78;
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		for (SuperByteBufferRenderState k : state.keys) {
			if (k != null) {
				k.submit(poseStack, collector);
			}
		}
		if (state.big == null) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0, 0.5F);
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
		poseStack.translate(-0.5F, 0, -0.5F);
		// screen glass: x 3.2..12.8, y 11.1..14.4, front at z 13.35 (model faces north)
		poseStack.translate(8 / 16F, 12.75F / 16F, 13.3F / 16F);
		poseStack.mulPose(Axis.YP.rotationDegrees(180));
		boolean twoLines = state.small != null;
		float bigScale = 0.014F;
		int bw = font.width(state.big);
		float maxW = 9F / 16F;
		if (bw * bigScale > maxW) {
			bigScale = maxW / bw;
		}
		poseStack.pushPose();
		poseStack.translate(0, twoLines ? 0.6F / 16F : 0, 0);
		poseStack.scale(bigScale, -bigScale, bigScale);
		collector.submitText(poseStack, -bw / 2F, -4F, state.big.getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET,
			LightCoordsUtil.FULL_BRIGHT, state.bigColor, 0, 0);
		poseStack.popPose();
		if (twoLines) {
			float s = 0.0058F;
			int sw = font.width(state.small);
			if (sw * s > maxW) {
				s = maxW / sw;
			}
			poseStack.pushPose();
			poseStack.translate(0, -1.05F / 16F, 0);
			poseStack.scale(s, -s, s);
			collector.submitText(poseStack, -sw / 2F, -4F, state.small.getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET,
				LightCoordsUtil.FULL_BRIGHT, state.smallColor, 0, 0);
			poseStack.popPose();
		}
		poseStack.popPose();
	}

	@Override
	public int getViewDistance() {
		return 48;
	}
}
