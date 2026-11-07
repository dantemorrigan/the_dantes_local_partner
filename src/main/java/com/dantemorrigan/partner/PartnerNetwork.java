package com.dantemorrigan.partner;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Packets. The server owns every decision; the client only asks and shows. */
public final class PartnerNetwork {
	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> newType(String name) {
		return new CustomPacketPayload.Type<>(LocalPartner.id(name));
	}

	/** C2S: pause-menu button. true = summon, false = dismiss. */
	public record Toggle(boolean summon) implements CustomPacketPayload {
		public static final Type<Toggle> TYPE = newType("toggle");
		public static final StreamCodec<ByteBuf, Toggle> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Toggle::summon, Toggle::new);
		@Override public Type<Toggle> type() { return TYPE; }
	}

	/** C2S: an order from the command wheel ({@link PartnerOrder#id}, or "inventory"). */
	public record Command(String order) implements CustomPacketPayload {
		public static final Type<Command> TYPE = newType("command");
		public static final StreamCodec<ByteBuf, Command> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Command::order, Command::new);
		@Override public Type<Command> type() { return TYPE; }
	}

	/**
	 * S2C: what the owner's partner is doing, for the pause button, the wheel, the HUD line and the live tactical map.
	 * state: 0 none, 1 present, 2 waiting (stashed: owner in a place it cannot follow), 3 dead (respawning).
	 */
	public record Status(int state, String order, String activity, String detail, int era, float health, int food,
						 int px, int py, int pz, String pois) implements CustomPacketPayload {
		public Status(int state, String order, String activity, String detail, int era, float health, int food) {
			this(state, order, activity, detail, era, health, food, 0, 0, 0, "");
		}

		public static final Type<Status> TYPE = newType("status");
		public static final StreamCodec<ByteBuf, Status> CODEC = StreamCodec.of(
				(buf, s) -> {
					ByteBufCodecs.VAR_INT.encode(buf, s.state);
					ByteBufCodecs.STRING_UTF8.encode(buf, s.order);
					ByteBufCodecs.STRING_UTF8.encode(buf, s.activity);
					ByteBufCodecs.STRING_UTF8.encode(buf, s.detail);
					ByteBufCodecs.VAR_INT.encode(buf, s.era);
					ByteBufCodecs.FLOAT.encode(buf, s.health);
					ByteBufCodecs.VAR_INT.encode(buf, s.food);
					ByteBufCodecs.VAR_INT.encode(buf, s.px);
					ByteBufCodecs.VAR_INT.encode(buf, s.py);
					ByteBufCodecs.VAR_INT.encode(buf, s.pz);
					ByteBufCodecs.STRING_UTF8.encode(buf, s.pois);
				},
				buf -> new Status(
						ByteBufCodecs.VAR_INT.decode(buf),
						ByteBufCodecs.STRING_UTF8.decode(buf),
						ByteBufCodecs.STRING_UTF8.decode(buf),
						ByteBufCodecs.STRING_UTF8.decode(buf),
						ByteBufCodecs.VAR_INT.decode(buf),
						ByteBufCodecs.FLOAT.decode(buf),
						ByteBufCodecs.VAR_INT.decode(buf),
						ByteBufCodecs.VAR_INT.decode(buf),
						ByteBufCodecs.VAR_INT.decode(buf),
						ByteBufCodecs.VAR_INT.decode(buf),
						ByteBufCodecs.STRING_UTF8.decode(buf)
				)
		);
		@Override public Type<Status> type() { return TYPE; }
	}

	/** S2C: a short status toast for the HUD card (accepted, assist, alert, recall, summon, dismiss...). */
	public record Toast(String key) implements CustomPacketPayload {
		public static final Type<Toast> TYPE = newType("toast");
		public static final StreamCodec<ByteBuf, Toast> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Toast::key, Toast::new);
		@Override public Type<Toast> type() { return TYPE; }
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Toggle.TYPE, Toggle.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Command.TYPE, Command.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Status.TYPE, Status.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Toast.TYPE, Toast.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Toggle.TYPE, (payload, ctx) -> {
			ServerPlayer player = ctx.player();
			if (payload.summon()) PartnerManager.summon(player);
			else PartnerManager.dismiss(player);
		});
		ServerPlayNetworking.registerGlobalReceiver(Command.TYPE, (payload, ctx) -> PartnerManager.command(ctx.player(), payload.order()));
	}

	public static void send(ServerPlayer player, CustomPacketPayload payload) {
		if (ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
	}

	private PartnerNetwork() {}
}
