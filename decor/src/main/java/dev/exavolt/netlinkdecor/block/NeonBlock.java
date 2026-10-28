package dev.exavolt.netlinkdecor.block;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;

/** A full block of glowing neon. */
public class NeonBlock extends Block {
	public final DyeColor color;

	public NeonBlock(DyeColor color, Properties properties) {
		super(properties);
		this.color = color;
	}
}
