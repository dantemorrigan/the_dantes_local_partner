package com.dantemorrigan.kingdoms;

import com.dantemorrigan.kingdoms.net.KNet;
import com.dantemorrigan.kingdoms.net.Sync;
import com.dantemorrigan.kingdoms.sim.Chronicle;
import com.dantemorrigan.kingdoms.sim.KingdomAI;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Terrain;
import com.dantemorrigan.kingdoms.world.CitizenEntity;
import com.dantemorrigan.kingdoms.world.Constructor;
import com.dantemorrigan.kingdoms.world.Population;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Dante's Kingdoms: kingdoms that live on their own. A realm of cells around the spawn is simulated day by day
 * (economy, construction, eras, diplomacy, wars), mirrored into the world as real towns and people when a player is
 * near, and shown on a strategy map where the player acts as a god.
 */
public final class KingdomsMod implements ModInitializer {
	public static final String NS = "kingdoms";
	public static final Logger LOG = LoggerFactory.getLogger("Kingdoms");

	private static final ResourceKey<EntityType<?>> CITIZEN_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("citizen"));
	public static final EntityType<CitizenEntity> CITIZEN = Registry.register(BuiltInRegistries.ENTITY_TYPE, CITIZEN_KEY,
			EntityType.Builder.<CitizenEntity>of(CitizenEntity::new, MobCategory.MISC)
					.sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10).build(CITIZEN_KEY));

	private static final AttachmentType<CompoundTag> REALM = AttachmentRegistry.<CompoundTag>builder()
			.persistent(CompoundTag.CODEC).buildAndRegister(id("realm"));

	private static Realm realm;
	private static MinecraftServer server;
	private static int dayClock;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NS, path);
	}

	public static Realm realm() {
		return realm;
	}

	public static MinecraftServer server() {
		return server;
	}

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(CITIZEN, CitizenEntity.createAttributes());
		KNet.init();
		KingdomCommand.init();
		ServerLifecycleEvents.SERVER_STARTED.register(KingdomsMod::start);
		ServerLifecycleEvents.BEFORE_SAVE.register((s, flush, force) -> store());
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> store());
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			realm = null;
			server = null;
			Sync.clear();
			Population.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(KingdomsMod::tick);
		LOG.info("The Dante's Kingdoms ready");
	}

	private static void start(MinecraftServer s) {
		server = s;
		ServerLevel level = s.overworld();
		realm = Realm.load(level.getAttachedOrElse(REALM, new CompoundTag()));
		if (!realm.initialized() && realm.originX == 0 && realm.originZ == 0) {
			BlockPos spawn = level.getRespawnData().pos();
			realm.originX = spawn.getX() - Realm.N / 2 * Realm.CELL;
			realm.originZ = spawn.getZ() - Realm.N / 2 * Realm.CELL;
		}
		realm.terrain = new Terrain(Realm.N * Realm.CELL / Terrain.SAMPLE);
		realm.terrain.generate(level, realm.originX, realm.originZ);
		LOG.info("Realm loaded: {} kingdoms, {} towns, day {}", realm.kingdoms.size(), realm.settlements.size(), realm.day);
	}

	private static void store() {
		if (server != null && realm != null) server.overworld().setAttached(REALM, realm.save());
	}

	private static void tick(MinecraftServer s) {
		Realm r = realm;
		if (r == null) return;
		ServerLevel level = s.overworld();
		if (!r.seeded && r.autoSeed && r.kingdoms.isEmpty()) {
			if (KingdomAI.seed(r)) announceNew(s, r);
		} else if (!r.seeded) {
			r.seeded = true;
		}
		if (r.speed > 0 && r.terrain != null && r.terrain.ready) {
			dayClock++;
			int ticksPerDay = Math.max(1, 20 / r.speed);
			if (dayClock >= ticksPerDay) {
				dayClock = 0;
				try {
					KingdomAI.day(r);
				} catch (Exception e) {
					LOG.error("Simulation day failed", e);
				}
				announceNew(s, r);
				Sync.day(s, r);
			}
		}
		Sync.tick(s, r);
		Constructor.tick(level, r);
		Population.tick(level, r);
	}

	private static int announced;

	/** Major events of the chronicle are told to everyone in chat. */
	private static void announceNew(MinecraftServer s, Realm r) {
		if (announced > r.chronicle.size()) announced = 0;
		for (int i = Math.max(announced, r.chronicle.size() - 5); i < r.chronicle.size(); i++) {
			Chronicle.Entry e = r.chronicle.get(i);
			if (e.level() < 2) continue;
			MutableComponent msg = Component.literal("♛ ").withStyle(net.minecraft.ChatFormatting.GOLD).append(e.text().withStyle(net.minecraft.ChatFormatting.YELLOW));
			for (ServerPlayer p : s.getPlayerList().getPlayers()) p.sendSystemMessage(msg);
		}
		announced = r.chronicle.size();
	}
}
