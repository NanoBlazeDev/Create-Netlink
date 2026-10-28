package dev.exavolt.netlink.client.render;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * GUI picture-in-picture of a Wall Extender before it is built: plates plus the structure at a fractional extension.
 * Positions are world positions; {@code center} is what the view orbits around and {@code scale} is GUI pixels per block.
 */
public record ExtenderPreviewState(
	Map<BlockPos, BlockState> structure,
	Map<BlockPos, BlockState> plates,
	Direction up,
	int plane,
	float sink,
	float yaw,
	float pitch,
	double cx,
	double cy,
	double cz,
	int x0,
	int y0,
	int x1,
	int y1,
	float scale,
	@Nullable ScreenRectangle scissorArea,
	@Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {
}
