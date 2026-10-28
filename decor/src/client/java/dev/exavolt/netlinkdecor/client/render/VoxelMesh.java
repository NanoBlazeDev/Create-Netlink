package dev.exavolt.netlinkdecor.client.render;

import com.mojang.blaze3d.platform.Transparency;
import dev.exavolt.netlinkdecor.voxel.VoxelData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The chunk mesh of a sculpted block: greedy-meshed faces in the canonical frame, turned to the block's facing, each face
 * textured with its material's texture for that side and mapped to block space so textures line up across voxels.
 */
public final class VoxelMesh implements BlockStateModelPart {
	private final Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
	private final List<BakedQuad> inner = new ArrayList<>();
	private final Material.Baked particle;
	private int flags;

	private VoxelMesh(Material.Baked particle) {
		this.particle = particle;
		for (Direction d : Direction.values()) {
			culled.put(d, new ArrayList<>());
		}
	}

	@Override
	public List<BakedQuad> getQuads(@Nullable Direction direction) {
		return direction == null ? inner : culled.get(direction);
	}

	@Override
	public boolean useAmbientOcclusion() {
		return true;
	}

	@Override
	public Material.Baked particleMaterial() {
		return particle;
	}

	@Override
	public int materialFlags() {
		return flags;
	}

	// ---------------------------------------------------------------- materials

	private static BlockStateModel modelOf(BlockState state) {
		return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
	}

	/** The texture info a material shows on one side. */
	private static BakedQuad.MaterialInfo infoFor(BlockState material, Direction side) {
		BlockStateModel model = modelOf(material);
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42L), parts);
		BakedQuad any = null;
		for (BlockStateModelPart part : parts) {
			List<BakedQuad> q = part.getQuads(side);
			if (!q.isEmpty()) {
				return q.getFirst().materialInfo();
			}
			for (BakedQuad b : part.getQuads(null)) {
				if (b.direction() == side) {
					return b.materialInfo();
				}
				if (any == null) {
					any = b;
				}
			}
		}
		if (any != null) {
			return any.materialInfo();
		}
		Material.Baked p = model.particleMaterial();
		Transparency t = p.sprite().transparency();
		return BakedQuad.MaterialInfo.of(p, t, -1, true, 0);
	}

	// ---------------------------------------------------------------- orientation helpers

	private static Direction turn(Direction d, int steps, boolean mirrored) {
		if (mirrored && d.getAxis() == Direction.Axis.X) {
			d = d.getOpposite();
		}
		for (int i = 0; i < steps; i++) {
			if (d.getAxis().isHorizontal()) {
				d = d.getClockWise();
			}
		}
		return d;
	}

	/** Canonical point (block units) to the block's world-aligned frame. */
	private static float[] turn(float x, float y, float z, int steps, boolean mirrored) {
		if (mirrored) {
			x = 1 - x;
		}
		for (int i = 0; i < steps; i++) {
			float nx = 1 - z, nz = x;
			x = nx;
			z = nz;
		}
		return new float[]{x, y, z};
	}

	private static float[] uv(Direction d, Vector3f p) {
		return switch (d) {
			case UP -> new float[]{p.x, p.z};
			case DOWN -> new float[]{p.x, 1 - p.z};
			case NORTH -> new float[]{1 - p.x, 1 - p.y};
			case SOUTH -> new float[]{p.x, 1 - p.y};
			case WEST -> new float[]{p.z, 1 - p.y};
			case EAST -> new float[]{1 - p.z, 1 - p.y};
		};
	}

	// ---------------------------------------------------------------- meshing

	public static VoxelMesh build(VoxelData data, int steps, boolean mirrored) {
		int res = data.res;
		BakedQuad.MaterialInfo[][] infos = new BakedQuad.MaterialInfo[data.paletteSize()][6];
		Material.Baked particle = null;
		for (int i = 1; i < data.paletteSize(); i++) {
			if (particle == null) {
				particle = modelOf(data.material(i)).particleMaterial();
			}
		}
		if (particle == null) {
			particle = modelOf(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()).particleMaterial();
		}
		VoxelMesh mesh = new VoxelMesh(particle);
		int[] mask = new int[res * res];
		for (Direction cdir : Direction.values()) {
			int axis = cdir.getAxis().ordinal();
			int sign = cdir.getAxisDirection().getStep();
			int ua = (axis + 1) % 3, va = (axis + 2) % 3;
			Direction wdir = turn(cdir, steps, mirrored);
			for (int slice = 0; slice < res; slice++) {
				int[] c = new int[3];
				int n = 0;
				for (int v = 0; v < res; v++) {
					for (int u = 0; u < res; u++) {
						c[axis] = slice;
						c[ua] = u;
						c[va] = v;
						int m = data.solid(c[0], c[1], c[2]) ? data.get(c[0], c[1], c[2]) : 0;
						if (m != 0) {
							c[axis] = slice + sign;
							if (data.solid(c[0], c[1], c[2])) {
								m = 0;
							}
						}
						mask[n++] = m;
					}
				}
				// greedy rectangles of one material
				for (int v = 0; v < res; v++) {
					for (int u = 0; u < res; ) {
						int m = mask[v * res + u];
						if (m == 0) {
							u++;
							continue;
						}
						int w = 1;
						while (u + w < res && mask[v * res + u + w] == m) {
							w++;
						}
						int h = 1;
						grow:
						while (v + h < res) {
							for (int k = 0; k < w; k++) {
								if (mask[(v + h) * res + u + k] != m) {
									break grow;
								}
							}
							h++;
						}
						for (int dv = 0; dv < h; dv++) {
							for (int du = 0; du < w; du++) {
								mask[(v + dv) * res + u + du] = 0;
							}
						}
						if (infos[m][wdir.ordinal()] == null) {
							infos[m][wdir.ordinal()] = infoFor(data.material(m), wdir);
						}
						mesh.addFace(axis, ua, va, sign, slice, u, v, w, h, res, steps, mirrored, wdir, infos[m][wdir.ordinal()]);
						u += w;
					}
				}
			}
		}
		return mesh;
	}

	private void addFace(int axis, int ua, int va, int sign, int slice, int u, int v, int w, int h, int res, int steps, boolean mirrored,
	                     Direction wdir, BakedQuad.MaterialInfo info) {
		float plane = (sign > 0 ? slice + 1 : slice) / (float) res;
		float[] a = new float[3], b = new float[3];
		a[axis] = plane;
		b[axis] = plane;
		a[ua] = u / (float) res;
		b[ua] = (u + w) / (float) res;
		a[va] = v / (float) res;
		b[va] = (v + h) / (float) res;
		float[] wa = turn(a[0], a[1], a[2], steps, mirrored), wb = turn(b[0], b[1], b[2], steps, mirrored);
		Vector3f from = new Vector3f(Math.min(wa[0], wb[0]), Math.min(wa[1], wb[1]), Math.min(wa[2], wb[2]));
		Vector3f to = new Vector3f(Math.max(wa[0], wb[0]), Math.max(wa[1], wb[1]), Math.max(wa[2], wb[2]));
		FaceInfo face = FaceInfo.fromFacing(wdir);
		TextureAtlasSprite sprite = info.sprite();
		Vector3f[] pos = new Vector3f[4];
		long[] uvs = new long[4];
		for (int i = 0; i < 4; i++) {
			pos[i] = face.getVertexInfo(i).select(from, to);
			float[] t = uv(wdir, pos[i]);
			uvs[i] = UVPair.pack(sprite.getU(clamp01(t[0])), sprite.getV(clamp01(t[1])));
		}
		BakedQuad quad = new BakedQuad(pos[0], pos[1], pos[2], pos[3], uvs[0], uvs[1], uvs[2], uvs[3], wdir, info);
		flags |= info.flags();
		boolean boundary = sign > 0 ? slice == res - 1 : slice == 0;
		(boundary ? culled.get(wdir) : inner).add(quad);
	}

	private static float clamp01(float f) {
		return Math.max(0, Math.min(1, f));
	}
}
