package dev.exavolt.netlink.item;

import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.network.NetlinkServer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Netlink Keyboard (TKL): Create's linked-controller flow with free key binds. Right-click toggles typing mode,
 * right-click an Advanced Redstone Link to bind the next key you press to its channel, sneak + right-click to edit
 * the bind list (needs an implanted terminal).
 */
public class KeyboardItem extends Item {
	/** Client hooks, set by the client entrypoint. */
	public static Runnable clientToggleActive = () -> {
	};
	public static Consumer<BlockPos> clientToggleBindMode = pos -> {
	};
	public static IntFunction<Component> keyName = key -> Component.literal("#" + key);

	public KeyboardItem(Properties properties) {
		super(properties);
	}

	/** Runs before the clicked block reacts. Null = not a keyboard. */
	public static @Nullable InteractionResult onItemUseFirst(Level level, Player player, ItemStack stack, InteractionHand hand,
	                                                         BlockHitResult ray, BlockPos pos) {
		if (!(stack.getItem() instanceof KeyboardItem item)) {
			return null;
		}
		if (player.mayBuild() && !player.isShiftKeyDown()) {
			BlockState hitState = level.getBlockState(pos);
			if (hitState.is(NetlinkRegistry.ADVANCED_REDSTONE_LINK)) {
				if (level.isClientSide()) {
					clientToggleBindMode.accept(pos);
				}
				player.getCooldowns().addCooldown(stack, 2);
				return InteractionResult.CONSUME;
			}
		}
		item.use(level, player, hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (hand == InteractionHand.MAIN_HAND) {
			if (player.isShiftKeyDown()) {
				if (!level.isClientSide() && player instanceof ServerPlayer sp && player.mayBuild()) {
					NetlinkServer.openKeyboardConfig(sp, hand);
				}
				return InteractionResult.SUCCESS;
			}
			if (level.isClientSide()) {
				clientToggleActive.run();
			}
			player.getCooldowns().addCooldown(player.getMainHandItem(), 2);
		} else if (!(player.getMainHandItem().getItem() instanceof KeyboardItem)) {
			if (level.isClientSide()) {
				clientToggleActive.run();
			}
			player.getCooldowns().addCooldown(player.getOffhandItem(), 2);
		}
		return InteractionResult.CONSUME;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		KeyboardConfig cfg = stack.getOrDefault(NetlinkRegistry.KEYBOARD_CONFIG, KeyboardConfig.EMPTY);
		int shown = 0;
		for (KeyboardConfig.Bind b : cfg.binds()) {
			if (b.channel().isEmpty()) {
				continue;
			}
			if (shown++ >= 8) {
				builder.accept(Component.translatable("tooltip.createnetlink.keyboard.more", cfg.binds().size() - 8).withStyle(ChatFormatting.DARK_GRAY));
				break;
			}
			builder.accept(Component.empty()
				.append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
				.append(keyName.apply(b.key()).copy().withStyle(ChatFormatting.DARK_AQUA))
				.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(b.channel()).withStyle(ChatFormatting.AQUA)));
		}
		if (shown == 0) {
			builder.accept(Component.translatable("tooltip.createnetlink.keyboard.unbound").withStyle(ChatFormatting.GRAY));
		}
	}
}
