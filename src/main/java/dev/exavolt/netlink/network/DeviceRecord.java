package dev.exavolt.netlink.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/**
 * One device that belongs to a network: an Advanced Redstone Link or a Bug. Kept in saved data so the network map
 * can list devices in unloaded chunks and so POST values survive chunk unloads.
 */
public final class DeviceRecord {
	public enum Kind {
		LINK, BUG, EXTENDER, ELEVATOR;

		public static Kind byId(int id) {
			return id == 1 ? BUG : id == 2 ? EXTENDER : id == 3 ? ELEVATOR : LINK;
		}

		public static Kind byName(String name) {
			for (Kind k : values()) {
				if (k.name().equals(name)) {
					return k;
				}
			}
			return LINK;
		}
	}

	public final Kind kind;
	public final ResourceKey<Level> dimension;
	public final BlockPos pos;
	public UUID owner;
	public String channel;
	public boolean listen;
	public int value;
	public Direction face;
	public String block;

	public DeviceRecord(Kind kind, ResourceKey<Level> dimension, BlockPos pos, UUID owner, String channel, boolean listen, int value,
	                    Direction face, String block) {
		this.kind = kind;
		this.dimension = dimension;
		this.pos = pos.immutable();
		this.owner = owner;
		this.channel = channel;
		this.listen = listen;
		this.value = value;
		this.face = face;
		this.block = block;
	}

	public static final Codec<DeviceRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("kind").forGetter(d -> d.kind.name()),
		Identifier.CODEC.fieldOf("dim").forGetter(d -> d.dimension.identifier()),
		BlockPos.CODEC.fieldOf("pos").forGetter(d -> d.pos),
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(d -> Optional.ofNullable(d.owner)),
		Codec.STRING.optionalFieldOf("channel", "").forGetter(d -> d.channel),
		Codec.BOOL.optionalFieldOf("listen", false).forGetter(d -> d.listen),
		Codec.INT.optionalFieldOf("value", 0).forGetter(d -> d.value),
		Direction.CODEC.optionalFieldOf("face", Direction.UP).forGetter(d -> d.face),
		Codec.STRING.optionalFieldOf("block", "").forGetter(d -> d.block)
	).apply(i, (kind, dim, pos, owner, channel, listen, value, face, block) -> new DeviceRecord(
		Kind.byName(kind),
		ResourceKey.create(Registries.DIMENSION, dim),
		pos,
		owner.orElse(null),
		channel,
		listen,
		value,
		face,
		block
	)));

	public static String key(Kind kind, ResourceKey<Level> dimension, BlockPos pos) {
		return kind.name() + "|" + dimension.identifier() + "|" + pos.asLong();
	}

	public String key() {
		return key(kind, dimension, pos);
	}

	/** Whether the device currently takes part in a channel. */
	public boolean active() {
		return owner != null && !channel.isEmpty();
	}

	public String channelKey() {
		return NetworkSavedData.channelKey(owner, channel);
	}
}
