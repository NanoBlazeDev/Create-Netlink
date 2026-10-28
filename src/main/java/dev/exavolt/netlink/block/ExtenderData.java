package dev.exavolt.netlink.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Everything a Wall Extender's master plate knows: the collapsed structure (relative to the master, in its extended
 * position), the blocks the plates replaced, the current extension and the step in progress.
 */
public final class ExtenderData {
	public record Part(BlockPos rel, BlockState state) {
	}

	public static final int DEFAULT_SPEED = 4;

	public Direction up = Direction.UP;
	public int hmax;
	public List<Part> structure = new ArrayList<>();
	public List<Part> plates = new ArrayList<>();
	public int extension;
	public int speed = DEFAULT_SPEED;
	public boolean invert;
	public @Nullable UUID owner;
	public String channel = "";
	/** Direction of the step in progress: +1 extending, -1 retracting, 0 resting. */
	public int moveDir;
	/** Ticks spent in the current step (a step lasts {@link #speed} ticks). */
	public int moveTick;

	private @Nullable List<Part> leading;

	// ---------------------------------------------------------------- compact codec (palette + packed ints)

	private record Packed(List<BlockState> palette, IntStream data) {
	}

	private static Packed pack(List<Part> parts) {
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> index = new HashMap<>();
		int[] out = new int[parts.size() * 4];
		for (int i = 0; i < parts.size(); i++) {
			Part p = parts.get(i);
			Integer id = index.get(p.state());
			if (id == null) {
				id = palette.size();
				palette.add(p.state());
				index.put(p.state(), id);
			}
			out[i * 4] = p.rel().getX();
			out[i * 4 + 1] = p.rel().getY();
			out[i * 4 + 2] = p.rel().getZ();
			out[i * 4 + 3] = id;
		}
		return new Packed(palette, IntStream.of(out));
	}

	private static List<Part> unpack(List<BlockState> palette, IntStream data) {
		int[] a = data.toArray();
		List<Part> parts = new ArrayList<>(a.length / 4);
		for (int i = 0; i + 3 < a.length; i += 4) {
			if (a[i + 3] >= 0 && a[i + 3] < palette.size() && !palette.get(a[i + 3]).isAir()) {
				parts.add(new Part(new BlockPos(a[i], a[i + 1], a[i + 2]), palette.get(a[i + 3])));
			}
		}
		return parts;
	}

	/** A block state that fails to load (removed mod, changed properties) becomes air instead of losing the whole wall. */
	private static final Codec<BlockState> LENIENT_STATE = BlockState.CODEC.orElse(
		net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

	public static final Codec<ExtenderData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Direction.CODEC.fieldOf("up").forGetter(d -> d.up),
		Codec.INT.fieldOf("hmax").forGetter(d -> d.hmax),
		LENIENT_STATE.listOf().fieldOf("palette").forGetter(d -> pack(d.structure).palette()),
		Codec.INT_STREAM.fieldOf("parts").forGetter(d -> pack(d.structure).data()),
		LENIENT_STATE.listOf().fieldOf("plate_palette").forGetter(d -> pack(d.plates).palette()),
		Codec.INT_STREAM.fieldOf("plate_parts").forGetter(d -> pack(d.plates).data()),
		Codec.INT.optionalFieldOf("extension", 0).forGetter(d -> d.extension),
		Codec.INT.optionalFieldOf("speed", DEFAULT_SPEED).forGetter(d -> d.speed),
		Codec.BOOL.optionalFieldOf("invert", false).forGetter(d -> d.invert),
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(d -> Optional.ofNullable(d.owner)),
		Codec.STRING.optionalFieldOf("channel", "").forGetter(d -> d.channel),
		Codec.INT.optionalFieldOf("move_dir", 0).forGetter(d -> d.moveDir),
		Codec.INT.optionalFieldOf("move_tick", 0).forGetter(d -> d.moveTick)
	).apply(i, (up, hmax, palette, parts, platePalette, plateParts, extension, speed, invert, owner, channel, moveDir, moveTick) -> {
		ExtenderData d = new ExtenderData();
		d.up = up;
		d.hmax = hmax;
		d.structure = unpack(palette, parts);
		d.plates = unpack(platePalette, plateParts);
		d.extension = extension;
		d.speed = speed;
		d.invert = invert;
		d.owner = owner.orElse(null);
		d.channel = channel;
		d.moveDir = moveDir;
		d.moveTick = moveTick;
		return d;
	}));

	// ---------------------------------------------------------------- geometry

	/** Distance of a relative position above the plate plane (the master sits on the plane). */
	public int depth(BlockPos rel) {
		return rel.getX() * up.getStepX() + rel.getY() * up.getStepY() + rel.getZ() * up.getStepZ();
	}

	/** World positions and states shown at the given extension (0 = fully collapsed into the plates). */
	public Map<BlockPos, BlockState> displayed(BlockPos master, int ext) {
		Map<BlockPos, BlockState> out = new HashMap<>();
		int sink = hmax - ext;
		for (Part p : structure) {
			if (depth(p.rel()) > sink) {
				out.put(master.offset(p.rel()).relative(up, -sink), p.state());
			}
		}
		return out;
	}

	/** Extension as a fraction while a step is running; {@code partial} is the partial tick (0 on the server). */
	public float frac(float partial) {
		if (moveDir == 0 || speed <= 0) {
			return extension;
		}
		return extension + moveDir * Math.min(1.0F, (moveTick + partial) / speed);
	}

	/** Offset (relative to the master) at which a part is drawn for a fractional extension. */
	public double[] offset(Part p, float f) {
		double sink = hmax - f;
		return new double[]{
			p.rel().getX() - up.getStepX() * sink,
			p.rel().getY() - up.getStepY() * sink,
			p.rel().getZ() - up.getStepZ() * sink
		};
	}

	/** How far a part sticks out of the plates at a fractional extension (&gt; 0 means at least partly visible). */
	public double height(Part p, float f) {
		return depth(p.rel()) - (hmax - f);
	}

	/** Call after changing {@link #structure}: drops cached geometry. */
	public void invalidate() {
		leading = null;
	}

	/** Parts with nothing of the structure in front of them along {@code up}: these are the faces that push. */
	public List<Part> leading() {
		if (leading == null) {
			Set<BlockPos> all = new HashSet<>();
			for (Part p : structure) {
				all.add(p.rel());
			}
			List<Part> out = new ArrayList<>();
			for (Part p : structure) {
				if (!all.contains(p.rel().relative(up))) {
					out.add(p);
				}
			}
			leading = out;
		}
		return leading;
	}

	/** World box of a part at a fractional extension. */
	public AABB box(BlockPos master, Part p, float f) {
		double[] o = offset(p, f);
		double x = master.getX() + o[0], y = master.getY() + o[1], z = master.getZ() + o[2];
		return new AABB(x, y, z, x + 1, y + 1, z + 1);
	}
}
