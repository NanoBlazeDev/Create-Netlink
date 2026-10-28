package dev.exavolt.netlink.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Netlink Glue — a fork of Create's Super Glue: no range limit, corners can be placed in mid-air (the glue shrinks
 * onto the blocks inside), sneak + right-click a block to auto-glue everything connected to it.
 */
public class NetlinkGlueItem extends Item {
	/** Client hooks: (hand, block hit or null) for a right-click. */
	public static BiConsumer<InteractionHand, BlockHitResult> clientUse = (hand, hit) -> {
	};

	public NetlinkGlueItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide() && hand == InteractionHand.MAIN_HAND) {
			clientUse.accept(hand, null);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("tooltip.createnetlink.glue.1").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.translatable("tooltip.createnetlink.glue.2").withStyle(ChatFormatting.DARK_AQUA));
		builder.accept(Component.translatable("tooltip.createnetlink.glue.3").withStyle(ChatFormatting.DARK_AQUA));
	}
}
