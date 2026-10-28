package dev.exavolt.netlinkdecor.voxel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Precise ray casting that sees single voxels of sculpted blocks, at any resolution. */
public final class VoxelRay {
	private VoxelRay() {
	}

	public record Hit(BlockPos pos, Vec3 point, Direction face, BlockState state) {
	}

	public static @Nullable Hit trace(BlockGetter level, Vec3 from, Vec3 to) {
		return BlockGetter.traverseBlocks(from, to, level, (lvl, pos) -> {
			BlockState state = lvl.getBlockState(pos);
			if (state.isAir()) {
				return null;
			}
			if (state.getBlock() instanceof VoxelBlock && lvl.getBlockEntity(pos) instanceof VoxelBlockEntity be) {
				return traceVoxels(be.data(), state, pos.immutable(), from, to);
			}
			VoxelShape shape = state.getShape(lvl, pos, CollisionContext.empty());
			BlockHitResult hit = shape.isEmpty() ? null : shape.clip(from, to, pos);
			return hit == null ? null : new Hit(pos.immutable(), hit.getLocation(), hit.getDirection(), state);
		}, lvl -> null);
	}

	private static @Nullable Hit traceVoxels(VoxelData data, BlockState state, BlockPos pos, Vec3 from, Vec3 to) {
		int res = data.res;
		int steps = VoxelBlock.steps(state);
		boolean mirrored = state.getValue(VoxelBlock.MIRRORED);
		double ox = from.x - pos.getX(), oy = from.y - pos.getY(), oz = from.z - pos.getZ();
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;

		// enter the unit box
		double t0 = 0, t1 = 1;
		int enterAxis = -1;
		double[] o = {ox, oy, oz};
		double[] d = {dx, dy, dz};
		for (int a = 0; a < 3; a++) {
			if (Math.abs(d[a]) < 1e-12) {
				if (o[a] < 0 || o[a] > 1) {
					return null;
				}
				continue;
			}
			double ta = (0 - o[a]) / d[a], tb = (1 - o[a]) / d[a];
			double near = Math.min(ta, tb), far = Math.max(ta, tb);
			if (near > t0) {
				t0 = near;
				enterAxis = a;
			}
			t1 = Math.min(t1, far);
		}
		if (t0 > t1) {
			return null;
		}

		double px = o[0] + d[0] * t0, py = o[1] + d[1] * t0, pz = o[2] + d[2] * t0;
		int[] cell = {
			clamp((int) Math.floor(px * res), res), clamp((int) Math.floor(py * res), res), clamp((int) Math.floor(pz * res), res)
		};
		int[] step = new int[3];
		double[] tMax = new double[3];
		double[] tDelta = new double[3];
		double[] p = {px, py, pz};
		for (int a = 0; a < 3; a++) {
			if (d[a] > 0) {
				step[a] = 1;
				tMax[a] = t0 + ((cell[a] + 1) / (double) res - p[a]) / d[a];
				tDelta[a] = 1.0 / res / d[a];
			} else if (d[a] < 0) {
				step[a] = -1;
				tMax[a] = t0 + (cell[a] / (double) res - p[a]) / d[a];
				tDelta[a] = -1.0 / res / d[a];
			} else {
				tMax[a] = Double.POSITIVE_INFINITY;
				tDelta[a] = Double.POSITIVE_INFINITY;
			}
		}
		double t = t0;
		int axis = enterAxis;
		for (int guard = 0; guard < res * 3 + 3; guard++) {
			int[] c = VoxelData.toCanonical(cell[0], cell[1], cell[2], res, steps, mirrored);
			if (data.solid(c[0], c[1], c[2])) {
				Vec3 point = new Vec3(pos.getX() + o[0] + d[0] * t, pos.getY() + o[1] + d[1] * t, pos.getZ() + o[2] + d[2] * t);
				Direction face = axis < 0 ? Direction.UP : faceOf(axis, -step[axis] == 0 ? -(int) Math.signum(d[axis]) : -step[axis]);
				return new Hit(pos, point, face, state);
			}
			int a = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
			if (tMax[a] > t1) {
				return null;
			}
			t = tMax[a];
			cell[a] += step[a];
			if (cell[a] < 0 || cell[a] >= res) {
				return null;
			}
			tMax[a] += tDelta[a];
			axis = a;
		}
		return null;
	}

	private static int clamp(int v, int res) {
		return Math.max(0, Math.min(res - 1, v));
	}

	private static Direction faceOf(int axis, int sign) {
		return switch (axis) {
			case 0 -> sign > 0 ? Direction.EAST : Direction.WEST;
			case 1 -> sign > 0 ? Direction.UP : Direction.DOWN;
			default -> sign > 0 ? Direction.SOUTH : Direction.NORTH;
		};
	}

	/** The voxel (at resolution {@code res}, in global voxel coordinates) just inside or just outside a hit. */
	public static long[] target(Hit hit, int res, boolean outside) {
		double e = 0.5 / res * (outside ? 1 : -1);
		Vec3 p = hit.point().add(hit.face().getStepX() * e, hit.face().getStepY() * e, hit.face().getStepZ() * e);
		return new long[]{(long) Math.floor(p.x * res), (long) Math.floor(p.y * res), (long) Math.floor(p.z * res)};
	}
}
