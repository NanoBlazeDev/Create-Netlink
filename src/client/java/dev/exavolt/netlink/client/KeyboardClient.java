package dev.exavolt.netlink.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zurrtum.create.AllSoundEvents;
import com.zurrtum.create.client.catnip.lang.FontHelper.Palette;
import com.zurrtum.create.client.catnip.outliner.Outliner;
import com.zurrtum.create.client.foundation.item.TooltipHelper;
import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.block.AdvancedRedstoneLinkBlockEntity;
import dev.exavolt.netlink.item.KeyboardConfig;
import dev.exavolt.netlink.item.KeyboardItem;
import dev.exavolt.netlink.net.NetlinkPackets;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Client half of the Netlink Keyboard. Same flow as Create's LinkedControllerClientHandler (toggle, bind mode,
 * keepalive packets, click sounds) but any key can be bound, and only bound keys are taken away from the game.
 * Runs at the start of the client tick so captured keys never reach vanilla keybinds.
 */
public final class KeyboardClient {
	private KeyboardClient() {
	}

	public static final int FIRST_KEY = GLFW.GLFW_KEY_SPACE;
	public static final int LAST_KEY = GLFW.GLFW_KEY_LAST;

	public static Mode MODE = Mode.IDLE;
	public static int PACKET_RATE = 5;
	public static Collection<Integer> currentlyPressed = new HashSet<>();
	private static BlockPos selectedLocation = BlockPos.ZERO;
	private static int packetCooldown;
	private static Set<Integer> bindPrev = new HashSet<>();
	private static final float[] pressPrev = new float[dev.exavolt.netlink.client.render.KeyboardLayout.KEYS.length];
	private static final float[] pressNow = new float[dev.exavolt.netlink.client.render.KeyboardLayout.KEYS.length];
	private static final java.util.Map<Integer, Integer> keyIndex = new java.util.HashMap<>();

	static {
		int[] keys = dev.exavolt.netlink.client.render.KeyboardLayout.KEYS;
		for (int k = 0; k < keys.length; k++) {
			keyIndex.put(keys[k], k);
		}
	}

	private static float equipPrev;
	private static float equipNow;

	/** 0..1: how far the keyboard is raised into view (first person), like Create's controller equip animation. */
	public static float equipAmount(float partialTicks) {
		return equipPrev + (equipNow - equipPrev) * partialTicks;
	}

	/** 0..1 travel of a modelled key, smoothed across ticks. */
	public static float pressAmount(int key, float partialTicks) {
		Integer k = keyIndex.get(key);
		if (k == null) {
			return 0;
		}
		return pressPrev[k] + (pressNow[k] - pressPrev[k]) * partialTicks;
	}

	private static void animateKeys(Minecraft mc) {
		equipPrev = equipNow;
		float equipTarget = MODE != Mode.IDLE ? 1 : 0;
		equipNow += (equipTarget - equipNow) * 0.35f;
		if (Math.abs(equipNow - equipTarget) < 0.005f) {
			equipNow = equipTarget;
		}
		int[] keys = dev.exavolt.netlink.client.render.KeyboardLayout.KEYS;
		boolean live = MODE != Mode.IDLE && mc.screen == null;
		for (int k = 0; k < keys.length; k++) {
			float target = live && InputConstants.isKeyDown(mc.getWindow(), keys[k]) ? 1 : 0;
			pressPrev[k] = pressNow[k];
			pressNow[k] += (target - pressNow[k]) * 0.65f;
			if (Math.abs(pressNow[k] - target) < 0.01f) {
				pressNow[k] = target;
			}
		}
	}

	public static void toggleBindMode(BlockPos location) {
		if (MODE == Mode.IDLE) {
			MODE = Mode.BIND;
			selectedLocation = location;
			bindPrev = downKeys(Minecraft.getInstance());
		} else {
			MODE = Mode.IDLE;
			Minecraft mc = Minecraft.getInstance();
			onReset(mc, mc.player);
		}
	}

	public static void toggle() {
		Minecraft mc = Minecraft.getInstance();
		if (MODE == Mode.IDLE) {
			MODE = Mode.ACTIVE;
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.translatable("message.createnetlink.keyboard_on").withStyle(ChatFormatting.AQUA));
			}
		} else {
			MODE = Mode.IDLE;
			onReset(mc, mc.player);
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.translatable("message.createnetlink.keyboard_off").withStyle(ChatFormatting.GRAY));
			}
		}
	}

	public static void reset() {
		MODE = Mode.IDLE;
		currentlyPressed.clear();
		packetCooldown = 0;
	}

	private static void onReset(Minecraft mc, @Nullable LocalPlayer player) {
		packetCooldown = 0;
		selectedLocation = BlockPos.ZERO;
		if (player != null && !currentlyPressed.isEmpty()) {
			ClientPlayNetworking.send(new NetlinkPackets.ControllerInput(List.copyOf(currentlyPressed), false));
		}
		currentlyPressed.clear();
		KeyMapping.setAll();
	}

	private static Set<Integer> downKeys(Minecraft mc) {
		Set<Integer> out = new HashSet<>();
		for (int key = FIRST_KEY; key <= LAST_KEY; key++) {
			if (InputConstants.isKeyDown(mc.getWindow(), key)) {
				out.add(key);
			}
		}
		return out;
	}

	/** Takes the given keys away from every vanilla/mod keybind for this tick. */
	private static void capture(Minecraft mc, Set<Integer> keys) {
		if (keys.isEmpty()) {
			return;
		}
		for (KeyMapping km : mc.options.keyMappings) {
			InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(km);
			if (bound.getType() == InputConstants.Type.KEYSYM && keys.contains(bound.getValue())) {
				km.setDown(false);
				while (km.consumeClick()) {
					// drain queued presses
				}
			}
		}
	}

	private static @Nullable ItemStack heldKeyboard(LocalPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (stack.getItem() instanceof KeyboardItem) {
			return stack;
		}
		stack = player.getOffhandItem();
		return stack.getItem() instanceof KeyboardItem ? stack : null;
	}

	public static boolean isHeld(@Nullable ItemStack stack) {
		LocalPlayer player = Minecraft.getInstance().player;
		return stack != null && player != null && heldKeyboard(player) == stack;
	}

	public static boolean isActiveFor(@Nullable ItemStack stack) {
		if (MODE == Mode.IDLE || stack == null) {
			return false;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && heldKeyboard(player) == stack;
	}

	public static void tick(Minecraft mc) {
		animateKeys(mc);
		if (MODE == Mode.IDLE) {
			return;
		}
		if (packetCooldown > 0) {
			packetCooldown--;
		}
		LocalPlayer player = mc.player;
		ClientLevel world = mc.level;
		if (player == null || world == null) {
			reset();
			return;
		}
		ItemStack held = heldKeyboard(player);
		if (player.isSpectator() || held == null) {
			MODE = Mode.IDLE;
			onReset(mc, player);
			return;
		}
		if (mc.screen != null || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_ESCAPE)) {
			MODE = Mode.IDLE;
			onReset(mc, player);
			return;
		}

		if (MODE == Mode.ACTIVE) {
			KeyboardConfig cfg = held.getOrDefault(NetlinkRegistry.KEYBOARD_CONFIG, KeyboardConfig.EMPTY);
			Set<Integer> bound = new HashSet<>();
			for (KeyboardConfig.Bind b : cfg.binds()) {
				bound.add(b.key());
			}
			Set<Integer> pressedKeys = new HashSet<>();
			for (int key : bound) {
				if (InputConstants.isKeyDown(mc.getWindow(), key)) {
					pressedKeys.add(key);
				}
			}

			Collection<Integer> newKeys = new HashSet<>(pressedKeys);
			Collection<Integer> releasedKeys = currentlyPressed;
			newKeys.removeAll(releasedKeys);
			releasedKeys.removeAll(pressedKeys);

			if (!releasedKeys.isEmpty()) {
				ClientPlayNetworking.send(new NetlinkPackets.ControllerInput(List.copyOf(releasedKeys), false));
				AllSoundEvents.CONTROLLER_CLICK.playAt(player.level(), player.blockPosition(), 1f, .5f, true);
			}
			if (!newKeys.isEmpty()) {
				ClientPlayNetworking.send(new NetlinkPackets.ControllerInput(List.copyOf(newKeys), true));
				packetCooldown = PACKET_RATE;
				AllSoundEvents.CONTROLLER_CLICK.playAt(player.level(), player.blockPosition(), 1f, .75f, true);
			}
			if (packetCooldown == 0 && !pressedKeys.isEmpty()) {
				ClientPlayNetworking.send(new NetlinkPackets.ControllerInput(List.copyOf(pressedKeys), true));
				packetCooldown = PACKET_RATE;
			}
			currentlyPressed = pressedKeys;
			capture(mc, bound);
			return;
		}

		// BIND mode: the next fresh key press takes the link's channel
		VoxelShape shape = world.getBlockState(selectedLocation).getShape(world, selectedLocation);
		if (!shape.isEmpty()) {
			Outliner.getInstance().showAABB("netlink_keyboard", shape.bounds().move(selectedLocation)).colored(0x22B6DC).lineWidth(1 / 16f);
		}
		Set<Integer> down = downKeys(mc);
		Set<Integer> fresh = new HashSet<>(down);
		fresh.removeAll(bindPrev);
		bindPrev = down;
		capture(mc, down);
		for (int key : fresh) {
			if (world.getBlockEntity(selectedLocation) instanceof AdvancedRedstoneLinkBlockEntity) {
				ClientPlayNetworking.send(new NetlinkPackets.ControllerBind(key, selectedLocation));
				player.sendOverlayMessage(Component.translatable("message.createnetlink.key_bound",
					InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName()).withStyle(ChatFormatting.AQUA));
				AllSoundEvents.CONTROLLER_CLICK.playAt(player.level(), player.blockPosition(), 1f, 1f, true);
			}
			MODE = Mode.IDLE;
			selectedLocation = BlockPos.ZERO;
			break;
		}
	}

	public static void renderOverlay(Minecraft mc, GuiGraphicsExtractor guiGraphics) {
		if (MODE != Mode.BIND) {
			return;
		}
		int width1 = guiGraphics.guiWidth();
		int height1 = guiGraphics.guiHeight();
		Matrix3x2fStack poseStack = guiGraphics.pose();
		poseStack.pushMatrix();

		List<Component> list = new ArrayList<>();
		list.add(Component.translatable("gui.createnetlink.keyboard.bind_mode").withStyle(ChatFormatting.AQUA));
		list.addAll(TooltipHelper.cutTextComponent(Component.translatable("gui.createnetlink.keyboard.press_key"), Palette.ALL_GRAY));

		int width = 0;
		int height = list.size() * mc.font.lineHeight;
		for (Component c : list) {
			width = Math.max(width, mc.font.width(c));
		}
		int x = (width1 / 3) - width / 2;
		int y = height1 - height - 24;
		guiGraphics.tooltip(
			mc.font,
			list.stream().map(Component::getVisualOrderText).map(ClientTooltipComponent::create).collect(Collectors.toList()),
			x,
			y,
			DefaultTooltipPositioner.INSTANCE,
			null
		);
		poseStack.popMatrix();
	}

	public enum Mode {
		IDLE, ACTIVE, BIND
	}
}
