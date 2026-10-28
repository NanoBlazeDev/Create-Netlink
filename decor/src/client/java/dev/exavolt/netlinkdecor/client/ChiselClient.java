package dev.exavolt.netlinkdecor.client;

import com.zurrtum.create.client.catnip.outliner.Outliner;
import dev.exavolt.netlinkdecor.client.screen.ChiselScreen;
import dev.exavolt.netlinkdecor.item.ChiselSettings;
import dev.exavolt.netlinkdecor.item.VoxelChiselItem;
import dev.exavolt.netlinkdecor.net.DecorPackets;
import dev.exavolt.netlinkdecor.voxel.VoxelEditor;
import dev.exavolt.netlinkdecor.voxel.VoxelRay;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Client controller of the Voxel Chisel. It takes over the attack and use keys while the chisel is held, ray casts
 * single voxels, outlines the brush and sends precise strokes to the server.
 */
public final class ChiselClient {
	private ChiselClient() {
	}

	private static final Object BRUSH = new Object();
	private static final Object FACE = new Object();
	private static final int REPEAT = 4;

	private static VoxelRay.@Nullable Hit hit;
	private static int attackRepeat;
	private static int useRepeat;
	/** Repeats only continue a stroke our own click started, and only while the button is physically held. */
	private static boolean attackHolding;
	private static boolean useHolding;

	/** Real button state from GLFW: Minecraft's own flag sticks when the button is released while a screen is open. */
	private static boolean down(Minecraft mc, int button) {
		return GLFW.glfwGetMouseButton(mc.getWindow().handle(), button) == GLFW.GLFW_PRESS;
	}
	private static @Nullable ChiselSettings shown;

	static @Nullable ItemStack held(LocalPlayer player) {
		ItemStack s = player.getMainHandItem();
		return s.getItem() instanceof VoxelChiselItem ? s : null;
	}

	public static void startTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		ItemStack chisel = held(player);
		if (chisel == null) {
			hit = null;
			shown = null;
			return;
		}
		ChiselSettings s = VoxelChiselItem.settings(chisel);
		Vec3 eye = player.getEyePosition();
		Vec3 to = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
		hit = VoxelRay.trace(mc.level, eye, to);

		if (mc.screen != null) {
			attackHolding = false;
			useHolding = false;
			return;
		}
		boolean attack = false;
		while (mc.options.keyAttack.consumeClick()) {
			attack = true;
		}
		boolean use = false;
		while (mc.options.keyUse.consumeClick()) {
			use = true;
		}
		// keep vanilla from mining or opening the block under the chisel
		mc.options.keyAttack.setDown(false);
		mc.options.keyUse.setDown(false);

		if (attack) {
			attackRepeat = REPEAT * 2;
			attackHolding = true;
			stroke(player, s, VoxelEditor.CARVE);
		} else if (attackHolding && down(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
			if (--attackRepeat <= 0) {
				attackRepeat = REPEAT;
				stroke(player, s, VoxelEditor.CARVE);
			}
		} else {
			attackHolding = false;
		}

		if (use) {
			useRepeat = REPEAT * 2;
			useHolding = !player.isShiftKeyDown();
			if (player.isShiftKeyDown()) {
				if (hit == null) {
					mc.setScreen(new ChiselScreen(s));
				} else {
					stroke(player, s, VoxelEditor.PICK);
				}
			} else {
				stroke(player, s, s.mode() == ChiselSettings.PAINT ? VoxelEditor.PAINT : VoxelEditor.ADD);
			}
		} else if (useHolding && !player.isShiftKeyDown() && down(mc, GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
			if (--useRepeat <= 0) {
				useRepeat = REPEAT;
				stroke(player, s, s.mode() == ChiselSettings.PAINT ? VoxelEditor.PAINT : VoxelEditor.ADD);
			}
		} else {
			useHolding = false;
		}

		if (!s.equals(shown)) {
			shown = s;
			BlockState m = VoxelChiselItem.material(player, chisel);
			player.sendOverlayMessage(Component.translatable("message.netlinkdecor.chisel_status", s.res(), s.brush(),
				Component.translatable(s.mode() == ChiselSettings.PAINT ? "gui.netlinkdecor.chisel.paint" : "gui.netlinkdecor.chisel.add"),
				m == null ? Component.translatable("gui.netlinkdecor.chisel.no_material") : m.getBlock().getName()).withStyle(ChatFormatting.AQUA));
		}
	}

	private static void stroke(LocalPlayer player, ChiselSettings s, int action) {
		if (hit == null) {
			return;
		}
		long[] g = VoxelRay.target(hit, s.res(), action == VoxelEditor.ADD);
		ClientPlayNetworking.send(new DecorPackets.ChiselUse(g[0], g[1], g[2], s.res(), action));
		player.swing(InteractionHand.MAIN_HAND);
	}

	/** Outlines the brush where the next stroke would land. */
	public static void endTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || hit == null) {
			return;
		}
		ItemStack chisel = held(player);
		if (chisel == null) {
			return;
		}
		ChiselSettings s = VoxelChiselItem.settings(chisel);
		boolean add = s.mode() == ChiselSettings.ADD && !player.isShiftKeyDown();
		long[] g = VoxelRay.target(hit, s.res(), add);
		int lo = -(s.brush() - 1) / 2, hi = s.brush() / 2;
		double r = s.res();
		AABB box = new AABB((g[0] + lo) / r, (g[1] + lo) / r, (g[2] + lo) / r, (g[0] + hi + 1) / r, (g[1] + hi + 1) / r, (g[2] + hi + 1) / r);
		Outliner.getInstance().showAABB(BRUSH, box.inflate(0.001))
			.colored(add ? 0x6FE6FF : s.mode() == ChiselSettings.PAINT ? 0xFFCB6B : 0xFF8060)
			.disableLineNormals()
			.lineWidth(1 / 64f);
		long[] c = VoxelRay.target(hit, s.res(), false);
		AABB voxel = new AABB(c[0] / r, c[1] / r, c[2] / r, (c[0] + 1) / r, (c[1] + 1) / r, (c[2] + 1) / r);
		Outliner.getInstance().showAABB(FACE, voxel.inflate(0.0005)).colored(0xFFFFFF).disableLineNormals().lineWidth(1 / 128f);
	}
}
