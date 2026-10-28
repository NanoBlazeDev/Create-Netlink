package dev.exavolt.netlink.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** Placed by right-clicking any block; the attach logic lives in NetlinkServer's use-block hook. */
public class BugItem extends Item {
	public BugItem(Properties properties) {
		super(properties);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("tooltip.createnetlink.bug.1").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.translatable("tooltip.createnetlink.bug.2").withStyle(ChatFormatting.DARK_AQUA));
	}
}
