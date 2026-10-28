package dev.exavolt.netlink.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Movement tuning of an Advanced Elevator Pulley, attached to the pulley's block entity and synced to clients so the
 * cabin moves identically on both sides. maxSpeed / acceleration: zero means "Create's default". speed: a multiplier
 * of the top speed (1 = normal, 0 = the cabin brakes and holds where it is).
 */
public record ElevatorTuning(float maxSpeed, float acceleration, float speed) {
	public static final ElevatorTuning NONE = new ElevatorTuning(0, 0, 1);

	public static final Codec<ElevatorTuning> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.FLOAT.optionalFieldOf("max_speed", 0f).forGetter(ElevatorTuning::maxSpeed),
		Codec.FLOAT.optionalFieldOf("acceleration", 0f).forGetter(ElevatorTuning::acceleration),
		Codec.FLOAT.optionalFieldOf("speed", 1f).forGetter(ElevatorTuning::speed)
	).apply(i, ElevatorTuning::new));

	public static final StreamCodec<ByteBuf, ElevatorTuning> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.FLOAT, ElevatorTuning::maxSpeed,
		ByteBufCodecs.FLOAT, ElevatorTuning::acceleration,
		ByteBufCodecs.FLOAT, ElevatorTuning::speed,
		ElevatorTuning::new
	);

	public boolean active() {
		return maxSpeed > 0 || acceleration > 0 || speed != 1;
	}
}
