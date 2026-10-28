package dev.exavolt.netlinkdecor.item;

import dev.exavolt.netlinkdecor.DecorRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * Sculpts any block into voxels. Left-click carves, right-click adds or paints, sneak + right-click on a block picks its
 * material, sneak + right-click in the air opens the settings. All clicks are handled client-side by the chisel
 * controller, which sends precise voxel targets to the server.
 */
public class VoxelChiselItem extends Item {
	public VoxelChiselItem(Properties properties) {
		super(properties);
	}

	public static ChiselSettings settings(ItemStack stack) {
		return stack.getOrDefault(DecorRegistry.CHISEL_SETTINGS, ChiselSettings.DEFAULT).sanitized();
	}

	/** The material to add or paint with: the block in the other hand, else the picked one. */
	public static BlockState material(Player player, ItemStack chisel) {
		ItemStack other = player.getMainHandItem() == chisel ? player.getOffhandItem() : player.getMainHandItem();
		if (other.getItem() instanceof BlockItem bi && !bi.getBlock().defaultBlockState().hasBlockEntity()
			&& !bi.getBlock().defaultBlockState().isAir()) {
			return bi.getBlock().defaultBlockState();
		}
		return settings(chisel).material().orElse(null);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		return InteractionResult.PASS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		return InteractionResult.PASS;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip,
	                            TooltipFlag flag) {
		ChiselSettings s = settings(stack);
		tooltip.accept(Component.translatable("tooltip.netlinkdecor.chisel.settings", s.res(), s.brush(),
			Component.translatable(s.shape() == ChiselSettings.SPHERE ? "gui.netlinkdecor.chisel.sphere" : "gui.netlinkdecor.chisel.cube"),
			Component.translatable(s.mode() == ChiselSettings.PAINT ? "gui.netlinkdecor.chisel.paint" : "gui.netlinkdecor.chisel.add"))
			.withStyle(ChatFormatting.AQUA));
		s.material().ifPresent(m -> tooltip.accept(Component.translatable("tooltip.netlinkdecor.chisel.material", m.getBlock().getName())
			.withStyle(ChatFormatting.GRAY)));
		tooltip.accept(Component.translatable("tooltip.netlinkdecor.chisel.1").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.accept(Component.translatable("tooltip.netlinkdecor.chisel.2").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.accept(Component.translatable("tooltip.netlinkdecor.chisel.3").withStyle(ChatFormatting.DARK_GRAY));
	}
}
