package dev.exavolt.netlinkdecor.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * Settings of a Voxel Chisel: resolution (voxels per block edge), brush size in voxels, brush shape, what right-click
 * does, and the material it adds or paints with (the block in the off hand wins over the picked one).
 */
public record ChiselSettings(int res, int brush, int shape, int mode, Optional<BlockState> material) {
	public static final int CUBE = 0;
	public static final int SPHERE = 1;
	public static final int ADD = 0;
	public static final int PAINT = 1;
	public static final int MAX_BRUSH = 16;
	public static final ChiselSettings DEFAULT = new ChiselSettings(16, 1, CUBE, ADD, Optional.empty());

	public ChiselSettings sanitized() {
		int r = res;
		if (r < 1 || r > 64 || Integer.bitCount(r) != 1) {
			r = 16;
		}
		return new ChiselSettings(r, Math.max(1, Math.min(MAX_BRUSH, brush)), shape == SPHERE ? SPHERE : CUBE, mode == PAINT ? PAINT : ADD,
			material.filter(s -> !s.isAir() && !s.hasBlockEntity()));
	}

	public ChiselSettings withMaterial(BlockState state) {
		return new ChiselSettings(res, brush, shape, mode, Optional.of(state));
	}

	public static final Codec<ChiselSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.optionalFieldOf("res", 16).forGetter(ChiselSettings::res),
		Codec.INT.optionalFieldOf("brush", 1).forGetter(ChiselSettings::brush),
		Codec.INT.optionalFieldOf("shape", CUBE).forGetter(ChiselSettings::shape),
		Codec.INT.optionalFieldOf("mode", ADD).forGetter(ChiselSettings::mode),
		BlockState.CODEC.optionalFieldOf("material").forGetter(ChiselSettings::material)
	).apply(i, ChiselSettings::new));

	public static final StreamCodec<ByteBuf, ChiselSettings> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, ChiselSettings::res,
		ByteBufCodecs.VAR_INT, ChiselSettings::brush,
		ByteBufCodecs.VAR_INT, ChiselSettings::shape,
		ByteBufCodecs.VAR_INT, ChiselSettings::mode,
		ByteBufCodecs.optional(ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)), ChiselSettings::material,
		ChiselSettings::new
	);
}
