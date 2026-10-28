package dev.exavolt.netlink.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Settings of an Advanced Elevator Pulley; scripts change them through elevator objects. */
public final class ElevatorParams {
	/** Top speed in blocks per tick (Create's own pulley tops out a little under 2). 0 = Create default. */
	public double maxSpeed;
	/** Acceleration in blocks per tick². 0 = Create default. */
	public double acceleration;
	/** Multiplier of the top speed: 1 = normal, 0 = brake and hold wherever the cabin is, 2 = twice as fast. */
	public double speed = 1;
	/** When locked, cabin panels can't send the elevator anywhere (scripts still can). */
	public boolean locked;
	/** Length of the Arrived pulse in ticks. */
	public int arrivalPulse = 10;

	public ElevatorParams() {
	}

	public ElevatorParams(double maxSpeed, double acceleration, double speed, boolean locked, int arrivalPulse) {
		this.maxSpeed = maxSpeed;
		this.acceleration = acceleration;
		this.speed = speed;
		this.locked = locked;
		this.arrivalPulse = arrivalPulse;
	}

	public static final Codec<ElevatorParams> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.DOUBLE.optionalFieldOf("max_speed", 0.0).forGetter(p -> p.maxSpeed),
		Codec.DOUBLE.optionalFieldOf("acceleration", 0.0).forGetter(p -> p.acceleration),
		Codec.DOUBLE.optionalFieldOf("speed", 1.0).forGetter(p -> p.speed),
		Codec.BOOL.optionalFieldOf("locked", false).forGetter(p -> p.locked),
		Codec.INT.optionalFieldOf("arrival_pulse", 10).forGetter(p -> p.arrivalPulse)
	).apply(i, ElevatorParams::new));
}
