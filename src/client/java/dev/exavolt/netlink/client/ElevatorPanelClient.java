package dev.exavolt.netlink.client;

import com.zurrtum.create.content.contraptions.AbstractContraptionEntity;
import com.zurrtum.create.content.contraptions.elevator.ElevatorContraption;
import dev.exavolt.netlink.block.ElevatorPanelInteraction;
import dev.exavolt.netlink.net.NetlinkPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;


/**
 * Keyboard focus on an Elevator Panel inside a cabin: right-click focuses it, number keys type a floor, Enter sends the
 * elevator there, Backspace corrects, Esc or another right-click releases. While focused, the keys don't reach the game.
 */
public final class ElevatorPanelClient {
	private ElevatorPanelClient() {
	}

	private static int entityId = -1;
	private static @Nullable BlockPos panel;
	private static String typed = "";
	/** Last panel that sent a floor: shows "→ floor" on its screen for a moment. */
	private static int sentEntity = -1;
	private static @Nullable BlockPos sentPanel;
	private static String sent = "";
	private static long sentAt;
	/** Per key: when it was pressed and whether it's still held (only for the focused panel). */
	private static final long[] pressedAt = new long[12];
	private static final boolean[] held = new boolean[12];
	private static int pressEntity = -1;
	private static @Nullable BlockPos pressPanel;

	public static void init() {
		ElevatorPanelInteraction.clientToggle = ElevatorPanelClient::toggle;
	}

	public static boolean focused() {
		return entityId >= 0;
	}

	public static boolean isFocused(int entity, BlockPos pos) {
		return entityId == entity && pos.equals(panel);
	}

	public static String typed() {
		return typed;
	}

	public static @Nullable String recentlySent(int entity, BlockPos pos) {
		return sentEntity == entity && pos.equals(sentPanel) && System.currentTimeMillis() - sentAt < 1500 ? sent : null;
	}

	/** 0..1 how far a key on this panel is pushed in. */
	public static float press(int entity, BlockPos pos, int key) {
		if (pressEntity != entity || !pos.equals(pressPanel)) {
			return 0;
		}
		if (held[key]) {
			return 1;
		}
		long dt = System.currentTimeMillis() - pressedAt[key];
		return dt < 0 || dt > 160 ? 0 : 1 - dt / 160f;
	}

	private static void toggle(AbstractContraptionEntity entity, BlockPos localPos) {
		Minecraft mc = Minecraft.getInstance();
		if (focused() && entityId == entity.getId() && localPos.equals(panel)) {
			release();
			return;
		}
		entityId = entity.getId();
		panel = localPos.immutable();
		pressEntity = entityId;
		pressPanel = panel;
		typed = "";
		if (mc.player != null) {
			mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, 1.6F);
			mc.player.sendOverlayMessage(Component.translatable("hud.createnetlink.panel.hint").withStyle(ChatFormatting.AQUA));
		}
	}

	private static void release() {
		Minecraft mc = Minecraft.getInstance();
		if (focused() && mc.player != null) {
			mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.3F, 1.0F);
			mc.player.sendOverlayMessage(Component.empty());
		}
		entityId = -1;
		panel = null;
		typed = "";
		java.util.Arrays.fill(held, false);
	}

	private static @Nullable AbstractContraptionEntity entity(Minecraft mc) {
		if (mc.level == null || entityId < 0) {
			return null;
		}
		Entity e = mc.level.getEntity(entityId);
		return e instanceof AbstractContraptionEntity ace && ace.isAlive() && ace.getContraption() instanceof ElevatorContraption ? ace : null;
	}

	public static void tick(Minecraft mc) {
		if (!focused()) {
			return;
		}
		AbstractContraptionEntity e = entity(mc);
		if (e == null || mc.player == null || mc.screen != null || panel == null) {
			release();
			return;
		}
		Vec3 at = e.toGlobalVector(Vec3.atCenterOf(panel), 1);
		if (mc.player.position().distanceToSqr(at) > 8 * 8) {
			release();
		}
	}

	private static int keyIndex(int key) {
		if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
			return key - GLFW.GLFW_KEY_1;
		}
		if (key >= GLFW.GLFW_KEY_KP_1 && key <= GLFW.GLFW_KEY_KP_9) {
			return key - GLFW.GLFW_KEY_KP_1;
		}
		if (key == GLFW.GLFW_KEY_0 || key == GLFW.GLFW_KEY_KP_0) {
			return 10;
		}
		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			return 9;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			return 11;
		}
		return -1;
	}

	/** Called from the keyboard mixin; returns true to swallow the key. */
	public static boolean onKey(int key, int action) {
		if (!focused() || Minecraft.getInstance().screen != null) {
			return false;
		}
		int idx = keyIndex(key);
		if (action == GLFW.GLFW_RELEASE) {
			if (idx >= 0) {
				held[idx] = false;
				pressedAt[idx] = System.currentTimeMillis();
				return true;
			}
			return key == GLFW.GLFW_KEY_MINUS;
		}
		if (idx >= 0 && action == GLFW.GLFW_PRESS) {
			held[idx] = true;
			pressedAt[idx] = System.currentTimeMillis();
		}
		if (idx >= 0 && idx != 9 && idx != 11) {
			int digit = idx == 10 ? 0 : idx + 1;
			if (typed.length() < 4) {
				typed += digit;
				click(1.8F);
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
			if (typed.isEmpty()) {
				typed = "-";
				click(1.5F);
			}
			return true;
		}
		if (idx == 9) {
			if (!typed.isEmpty()) {
				typed = typed.substring(0, typed.length() - 1);
				click(1.2F);
			}
			return true;
		}
		if (idx == 11) {
			if (!typed.isEmpty() && !typed.equals("-")) {
				ClientPlayNetworking.send(new NetlinkPackets.ElevatorPanelSelect(entityId, typed));
				sent = typed;
				sentAt = System.currentTimeMillis();
				sentEntity = entityId;
				sentPanel = panel;
				click(2.0F);
				// let go of the panel once a floor is chosen; the key still springs back
				held[11] = false;
				entityId = -1;
				panel = null;
				typed = "";
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			release();
			return true;
		}
		return false;
	}

	private static void click(float pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.playSound(SoundEvents.STONE_BUTTON_CLICK_ON, 0.25F, pitch);
		}
	}
}
