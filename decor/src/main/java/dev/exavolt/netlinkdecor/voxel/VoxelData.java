package dev.exavolt.netlinkdecor.voxel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The voxels of one block, in its canonical (north-facing, unmirrored) frame. {@code res} voxels per block edge (a power
 * of two up to {@link #MAX_RES}); every voxel is an index into {@code palette}, 0 = empty. Instances are never changed
 * after they are shared: edits work on a {@link #copy()}, so chunk meshing threads can read safely.
 */
public final class VoxelData {
	public static final int MAX_RES = 64;
	public static final int MAX_MATERIALS = 255;

	public final int res;
	private final List<BlockState> palette;
	private final byte[] voxels;

	private VoxelData(int res, List<BlockState> palette, byte[] voxels) {
		this.res = res;
		this.palette = palette;
		this.voxels = voxels;
	}

	public static VoxelData empty(int res) {
		List<BlockState> p = new ArrayList<>();
		p.add(Blocks.AIR.defaultBlockState());
		return new VoxelData(res, p, new byte[res * res * res]);
	}

	public static VoxelData filled(int res, BlockState material) {
		VoxelData d = empty(res);
		d.palette.add(material);
		Arrays.fill(d.voxels, (byte) 1);
		return d;
	}

	public VoxelData copy() {
		return new VoxelData(res, new ArrayList<>(palette), voxels.clone());
	}

	public static boolean validRes(int r) {
		return r >= 1 && r <= MAX_RES && Integer.bitCount(r) == 1;
	}

	// ---------------------------------------------------------------- access

	public int index(int x, int y, int z) {
		return (y * res + z) * res + x;
	}

	public boolean inside(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < res && y < res && z < res;
	}

	public int get(int x, int y, int z) {
		return voxels[index(x, y, z)] & 0xFF;
	}

	public boolean solid(int x, int y, int z) {
		return inside(x, y, z) && voxels[index(x, y, z)] != 0;
	}

	public BlockState material(int paletteIndex) {
		return palette.get(paletteIndex);
	}

	public int paletteSize() {
		return palette.size();
	}

	public void set(int x, int y, int z, int paletteIndex) {
		voxels[index(x, y, z)] = (byte) paletteIndex;
	}

	/** Palette index of a material, adding it (and compacting the palette when full). -1 if there is no room. */
	public int indexOf(BlockState material) {
		if (material.isAir()) {
			return 0;
		}
		int i = palette.indexOf(material);
		if (i >= 0) {
			return i;
		}
		if (palette.size() > MAX_MATERIALS) {
			compact();
			if (palette.size() > MAX_MATERIALS) {
				return -1;
			}
		}
		palette.add(material);
		return palette.size() - 1;
	}

	/** Drops palette entries no voxel uses any more. */
	public void compact() {
		boolean[] used = new boolean[palette.size()];
		used[0] = true;
		for (byte b : voxels) {
			used[b & 0xFF] = true;
		}
		int[] remap = new int[palette.size()];
		List<BlockState> next = new ArrayList<>();
		for (int i = 0; i < palette.size(); i++) {
			if (used[i]) {
				remap[i] = next.size();
				next.add(palette.get(i));
			}
		}
		if (next.size() == palette.size()) {
			return;
		}
		for (int i = 0; i < voxels.length; i++) {
			voxels[i] = (byte) remap[voxels[i] & 0xFF];
		}
		palette.clear();
		palette.addAll(next);
	}

	public boolean isEmpty() {
		for (byte b : voxels) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	/** The single material filling every voxel, or null. */
	public BlockState uniform() {
		int first = voxels[0] & 0xFF;
		if (first == 0) {
			return null;
		}
		for (byte b : voxels) {
			if ((b & 0xFF) != first) {
				return null;
			}
		}
		return palette.get(first);
	}

	/** Same shape at a finer resolution (each voxel becomes a cube of voxels). */
	public VoxelData upsample(int newRes) {
		if (newRes <= res) {
			return copy();
		}
		int f = newRes / res;
		VoxelData d = new VoxelData(newRes, new ArrayList<>(palette), new byte[newRes * newRes * newRes]);
		for (int y = 0; y < newRes; y++) {
			for (int z = 0; z < newRes; z++) {
				for (int x = 0; x < newRes; x++) {
					d.voxels[d.index(x, y, z)] = voxels[index(x / f, y / f, z / f)];
				}
			}
		}
		return d;
	}

	/** Brightest light any material inside gives off. */
	public int lightEmission() {
		boolean[] used = new boolean[palette.size()];
		for (byte b : voxels) {
			used[b & 0xFF] = true;
		}
		int light = 0;
		for (int i = 1; i < palette.size(); i++) {
			if (used[i]) {
				light = Math.max(light, palette.get(i).getLightEmission());
			}
		}
		return light;
	}

	// ---------------------------------------------------------------- orientation

	/** Canonical voxel coordinates for a voxel given in the block's world-aligned frame. */
	public static int[] toCanonical(int x, int y, int z, int res, int steps, boolean mirrored) {
		// world = R^steps(M(c)); undo the rotation first, then the mirror
		for (int i = 0; i < (4 - steps) % 4; i++) {
			int nx = res - 1 - z, nz = x; // clockwise
			x = nx;
			z = nz;
		}
		if (mirrored) {
			x = res - 1 - x;
		}
		return new int[]{x, y, z};
	}

	public static int[] toWorld(int x, int y, int z, int res, int steps, boolean mirrored) {
		if (mirrored) {
			x = res - 1 - x;
		}
		for (int i = 0; i < steps; i++) {
			int nx = res - 1 - z, nz = x;
			x = nx;
			z = nz;
		}
		return new int[]{x, y, z};
	}

	// ---------------------------------------------------------------- shape

	/** Collision/outline shape in the canonical frame, at most 16 cells per edge so it stays cheap. */
	public VoxelShape canonicalShape() {
		int sr = Math.min(res, 16);
		int f = res / sr;
		boolean[] cells = new boolean[sr * sr * sr];
		for (int y = 0; y < res; y++) {
			for (int z = 0; z < res; z++) {
				for (int x = 0; x < res; x++) {
					if (voxels[index(x, y, z)] != 0) {
						cells[((y / f) * sr + z / f) * sr + x / f] = true;
					}
				}
			}
		}
		// greedy boxes: grow along x, then z, then y
		boolean[] done = new boolean[cells.length];
		VoxelShape shape = Shapes.empty();
		List<VoxelShape> boxes = new ArrayList<>();
		for (int y = 0; y < sr; y++) {
			for (int z = 0; z < sr; z++) {
				for (int x = 0; x < sr; x++) {
					int i = (y * sr + z) * sr + x;
					if (!cells[i] || done[i]) {
						continue;
					}
					int x1 = x;
					while (x1 + 1 < sr && cells[(y * sr + z) * sr + x1 + 1] && !done[(y * sr + z) * sr + x1 + 1]) {
						x1++;
					}
					int z1 = z;
					grow:
					while (z1 + 1 < sr) {
						for (int xx = x; xx <= x1; xx++) {
							int j = (y * sr + z1 + 1) * sr + xx;
							if (!cells[j] || done[j]) {
								break grow;
							}
						}
						z1++;
					}
					int y1 = y;
					growY:
					while (y1 + 1 < sr) {
						for (int zz = z; zz <= z1; zz++) {
							for (int xx = x; xx <= x1; xx++) {
								int j = ((y1 + 1) * sr + zz) * sr + xx;
								if (!cells[j] || done[j]) {
									break growY;
								}
							}
						}
						y1++;
					}
					for (int yy = y; yy <= y1; yy++) {
						for (int zz = z; zz <= z1; zz++) {
							for (int xx = x; xx <= x1; xx++) {
								done[(yy * sr + zz) * sr + xx] = true;
							}
						}
					}
					double s = 16.0 / sr;
					boxes.add(Block.box(x * s, y * s, z * s, (x1 + 1) * s, (y1 + 1) * s, (z1 + 1) * s));
				}
			}
		}
		for (VoxelShape b : boxes) {
			shape = Shapes.joinUnoptimized(shape, b, net.minecraft.world.phys.shapes.BooleanOp.OR);
		}
		return shape.optimize();
	}

	// ---------------------------------------------------------------- serialization (run-length encoded)

	private int[] encode() {
		IntStream.Builder out = IntStream.builder();
		int i = 0;
		while (i < voxels.length) {
			int v = voxels[i] & 0xFF;
			int run = 1;
			while (i + run < voxels.length && (voxels[i + run] & 0xFF) == v) {
				run++;
			}
			out.add((run << 8) | v);
			i += run;
		}
		return out.build().toArray();
	}

	private static VoxelData decode(int res, List<BlockState> palette, int[] runs) {
		if (!validRes(res)) {
			res = 16;
		}
		List<BlockState> p = new ArrayList<>(palette);
		if (p.isEmpty() || !p.getFirst().isAir()) {
			p.addFirst(Blocks.AIR.defaultBlockState());
		}
		byte[] v = new byte[res * res * res];
		int i = 0;
		for (int r : runs) {
			int run = r >>> 8, val = r & 0xFF;
			if (val >= p.size()) {
				val = 0;
			}
			for (int k = 0; k < run && i < v.length; k++) {
				v[i++] = (byte) val;
			}
		}
		return new VoxelData(res, p, v);
	}

	public static final Codec<VoxelData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("res").forGetter(d -> d.res),
		BlockState.CODEC.listOf().fieldOf("palette").forGetter(d -> d.palette),
		Codec.INT_STREAM.fieldOf("voxels").forGetter(d -> IntStream.of(d.encode()))
	).apply(i, (res, palette, runs) -> decode(res, palette, runs.toArray())));

	public static final StreamCodec<ByteBuf, VoxelData> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, d -> d.res,
		ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY).apply(ByteBufCodecs.list()), d -> d.palette,
		ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), d -> Arrays.stream(d.encode()).boxed().toList(),
		(res, palette, runs) -> decode(res, palette, runs.stream().mapToInt(Integer::intValue).toArray())
	);

	@Override
	public boolean equals(Object o) {
		return o instanceof VoxelData d && d.res == res && d.palette.equals(palette) && Arrays.equals(d.voxels, voxels);
	}

	@Override
	public int hashCode() {
		return res * 31 + Arrays.hashCode(voxels);
	}
}
