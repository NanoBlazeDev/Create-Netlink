package dev.exavolt.netlink.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Settings stored on a Netlink Keyboard: the network it talks to and any number of key → channel binds. */
public record KeyboardConfig(Optional<UUID> owner, List<Bind> binds) {
	public static final int MAX_BINDS = 48;
	public static final KeyboardConfig EMPTY = new KeyboardConfig(Optional.empty(), List.of());

	public static final int POWER = 0;
	public static final int TOGGLE = 1;
	public static final int PULSE = 2;
	public static final int TOGGLE_PULSE = 3;
	public static final int ACTIONS = 4;
	public static final int DEFAULT_TICKS = 10;
	public static final int MAX_TICKS = 1200;

	/**
	 * key is a GLFW key code. action: POWER (while held), TOGGLE (latch on/off), PULSE (ticks long),
	 * TOGGLE_PULSE (starts/stops a pulse train: ticks on, ticks off).
	 */
	public record Bind(int key, String channel, int action, int ticks) {
		public Bind(int key, String channel) {
			this(key, channel, POWER, DEFAULT_TICKS);
		}

		public static final Codec<Bind> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("key").forGetter(Bind::key),
			Codec.STRING.fieldOf("channel").forGetter(Bind::channel),
			Codec.INT.optionalFieldOf("action", POWER).forGetter(Bind::action),
			Codec.INT.optionalFieldOf("ticks", DEFAULT_TICKS).forGetter(Bind::ticks)
		).apply(i, Bind::new));

		public boolean usesTicks() {
			return action == PULSE || action == TOGGLE_PULSE;
		}
	}

	public static final Codec<KeyboardConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(KeyboardConfig::owner),
		Bind.CODEC.listOf().optionalFieldOf("binds", List.of()).forGetter(KeyboardConfig::binds)
	).apply(i, KeyboardConfig::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, KeyboardConfig> STREAM_CODEC = StreamCodec.of(
		(buf, cfg) -> {
			buf.writeBoolean(cfg.owner.isPresent());
			cfg.owner.ifPresent(buf::writeUUID);
			buf.writeVarInt(cfg.binds.size());
			for (Bind b : cfg.binds) {
				buf.writeVarInt(b.key());
				buf.writeUtf(b.channel(), 128);
				buf.writeVarInt(b.action());
				buf.writeVarInt(b.ticks());
			}
		},
		buf -> {
			Optional<UUID> owner = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
			int n = Math.min(buf.readVarInt(), MAX_BINDS);
			List<Bind> list = new ArrayList<>();
			for (int k = 0; k < n; k++) {
				list.add(new Bind(buf.readVarInt(), buf.readUtf(128), buf.readVarInt(), buf.readVarInt()));
			}
			return new KeyboardConfig(owner, list);
		}
	);

	/** Every bind on this key (a key may drive several channels). */
	public List<Bind> bindsFor(int key) {
		List<Bind> out = new ArrayList<>();
		for (Bind b : binds) {
			if (b.key() == key && !b.channel().isEmpty()) {
				out.add(b);
			}
		}
		return out;
	}

	public KeyboardConfig withBind(int key, String channel, UUID newOwner) {
		List<Bind> list = new ArrayList<>(binds);
		boolean replaced = false;
		for (int k = 0; k < list.size(); k++) {
			if (list.get(k).key() == key) {
				Bind old = list.get(k);
				list.set(k, new Bind(key, channel, old.action(), old.ticks()));
				replaced = true;
				break;
			}
		}
		if (!replaced && list.size() < MAX_BINDS) {
			list.add(new Bind(key, channel));
		}
		return new KeyboardConfig(Optional.of(newOwner), List.copyOf(list));
	}
}
