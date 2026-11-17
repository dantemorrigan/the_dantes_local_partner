package com.dantemorrigan.kingdoms.net;

import com.dantemorrigan.kingdoms.KingdomsMod;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Packets of the strategic map. The server simulates and decides; clients watch and ask for divine acts. */
public final class KNet {
	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> newType(String name) {
		return new CustomPacketPayload.Type<>(KingdomsMod.id(name));
	}

	/** C2S: the map screen opened or closed; {@code terrain}: the client still needs the land. */
	public record View(boolean open, boolean terrain) implements CustomPacketPayload {
		public static final Type<View> TYPE = newType("view");
		public static final StreamCodec<ByteBuf, View> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, View::open, ByteBufCodecs.BOOL, View::terrain, View::new);
		@Override public Type<View> type() { return TYPE; }
	}

	/** C2S: a divine act. */
	public record God(String action, int a, int b, int x, int z) implements CustomPacketPayload {
		public static final Type<God> TYPE = newType("god");
		public static final StreamCodec<ByteBuf, God> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, God::action, ByteBufCodecs.VAR_INT, God::a, ByteBufCodecs.VAR_INT, God::b,
				ByteBufCodecs.VAR_INT, God::x, ByteBufCodecs.VAR_INT, God::z, God::new);
		@Override public Type<God> type() { return TYPE; }
	}

	/** S2C: a band of terrain rows. */
	public record Land(int size, int originX, int originZ, int row, int rows, byte[] cls, byte[] height) implements CustomPacketPayload {
		public static final Type<Land> TYPE = newType("land");
		public static final StreamCodec<ByteBuf, Land> CODEC = StreamCodec.of(
				(buf, p) -> {
					ByteBufCodecs.VAR_INT.encode(buf, p.size);
					ByteBufCodecs.INT.encode(buf, p.originX);
					ByteBufCodecs.INT.encode(buf, p.originZ);
					ByteBufCodecs.VAR_INT.encode(buf, p.row);
					ByteBufCodecs.VAR_INT.encode(buf, p.rows);
					ByteBufCodecs.BYTE_ARRAY.encode(buf, p.cls);
					ByteBufCodecs.BYTE_ARRAY.encode(buf, p.height);
				},
				buf -> new Land(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.INT.decode(buf), ByteBufCodecs.INT.decode(buf),
						ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BYTE_ARRAY.decode(buf), ByteBufCodecs.BYTE_ARRAY.decode(buf)));
		@Override public Type<Land> type() { return TYPE; }
	}

	/** S2C: political state; {@code full} replaces everything, otherwise a delta. */
	public record State(CompoundTag data) implements CustomPacketPayload {
		public static final Type<State> TYPE = newType("state");
		public static final StreamCodec<ByteBuf, State> CODEC = StreamCodec.composite(ByteBufCodecs.TRUSTED_COMPOUND_TAG, State::data, State::new);
		@Override public Type<State> type() { return TYPE; }
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(View.TYPE, View.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(God.TYPE, God.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Land.TYPE, Land.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(State.TYPE, State.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(View.TYPE, (p, ctx) -> Sync.view(ctx.player(), p.open(), p.terrain()));
		ServerPlayNetworking.registerGlobalReceiver(God.TYPE, (p, ctx) -> com.dantemorrigan.kingdoms.GodPowers.act(ctx.player(), p.action(), p.a(), p.b(), p.x(), p.z()));
	}

	public static void send(ServerPlayer player, CustomPacketPayload payload) {
		if (ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
	}

	private KNet() {}
}
