package com.dantemorrigan.kingdoms;

import com.dantemorrigan.kingdoms.net.Sync;
import com.dantemorrigan.kingdoms.sim.Army;
import com.dantemorrigan.kingdoms.sim.Chronicle;
import com.dantemorrigan.kingdoms.sim.Era;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.KingdomAI;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import com.dantemorrigan.kingdoms.world.CitizenEntity;
import com.dantemorrigan.kingdoms.world.Constructor;
import com.dantemorrigan.kingdoms.world.Population;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The player as a god: found and destroy kingdoms, bless and curse, strike with lightning and meteors, start wars and
 * force peace, forge alliances, give land, raise armies, push a kingdom into a new age or into rebellion, set the
 * pace of time, and look down on any place of the realm.
 */
public final class GodPowers {
	private record Return(GameType mode, Vec3 pos, float yaw, float pitch) {}

	private static final Map<UUID, Return> OBSERVERS = new HashMap<>();

	public static boolean allowed(ServerPlayer p) {
		var server = p.level().getServer();
		return (server != null && server.isSingleplayerOwner(p.nameAndId())) || p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	public static void act(ServerPlayer p, String action, int a, int b, int x, int z) {
		Realm r = KingdomsMod.realm();
		if (r == null) return;
		if (!allowed(p) && !action.equals("observe") && !action.equals("return")) {
			p.sendOverlayMessage(Component.translatable("kingdoms.god.denied"));
			return;
		}
		ServerLevel level = p.level().getServer().overworld();
		Kingdom ka = r.kingdoms.get(a), kb = r.kingdoms.get(b);
		if (ka != null && !ka.alive) ka = null;
		if (kb != null && !kb.alive) kb = null;
		int cx = r.cellX(x), cz = r.cellZ(z);
		switch (action) {
			case "speed" -> r.speed = Math.max(0, Math.min(20, a));
			case "found" -> {
				if (!KingdomAI.goodSite(r, cx, cz)) {
					p.sendOverlayMessage(Component.translatable("kingdoms.god.bad_site"));
					return;
				}
				Kingdom k = KingdomAI.found(r, cx, cz, null);
				r.log(2, x, z, "kingdoms.log.god_found", Chronicle.kingdom(k));
				effect(level, x, z, true);
			}
			case "destroy" -> {
				if (ka == null) return;
				for (Settlement s : r.settlementsOf(ka.id)) {
					for (Settlement.Building bd : s.buildings) if (level.getRandom().nextInt(3) == 0) Constructor.ruin(level, bd);
					for (CitizenEntity c : new ArrayList<>(Population.of(s.id))) c.discard();
				}
				r.log(2, 0, 0, "kingdoms.log.god_destroy", Chronicle.kingdom(ka));
				KingdomAI.destroy(r, ka, null);
			}
			case "bless" -> {
				if (ka == null) return;
				ka.food += 80;
				ka.wood += 60;
				ka.stone += 40;
				ka.gold += 60;
				ka.population *= 1.12f;
				ka.stability = Math.min(100, ka.stability + 18);
				Settlement c = r.capitalOf(ka);
				if (c != null) effect(level, c.x, c.z, true);
				r.log(1, c == null ? 0 : c.x, c == null ? 0 : c.z, "kingdoms.log.god_bless", Chronicle.kingdom(ka));
			}
			case "plague" -> {
				if (ka == null) return;
				ka.population *= 0.68f;
				ka.soldiers *= 0.75f;
				ka.stability = Math.max(0, ka.stability - 14);
				Settlement c = r.capitalOf(ka);
				if (c != null) effect(level, c.x, c.z, false);
				r.log(1, c == null ? 0 : c.x, c == null ? 0 : c.z, "kingdoms.log.god_plague", Chronicle.kingdom(ka));
			}
			case "smite", "meteor" -> strike(r, level, x, z, action.equals("meteor"));
			case "war" -> {
				if (ka == null || kb == null || ka == kb) return;
				KingdomAI.declareWar(r, ka, kb, true);
			}
			case "peace" -> {
				if (ka == null || kb == null) return;
				KingdomAI.peace(r, ka, kb);
			}
			case "ally" -> {
				if (ka == null || kb == null || ka == kb) return;
				KingdomAI.ally(r, ka, kb);
			}
			case "gift" -> {
				if (ka == null || !r.land(cx, cz)) return;
				for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) if (r.land(cx + dx, cz + dz)) r.setOwner(cx + dx, cz + dz, ka.id);
				Settlement town = r.settlementAtCell(cx, cz);
				if (town != null && town.kingdom != ka.id) town.kingdom = ka.id;
			}
			case "era" -> {
				if (ka == null || ka.era >= Era.INDUSTRIAL.ordinal()) return;
				ka.knowledge = Math.max(ka.knowledge, ka.era().next().knowledge);
			}
			case "army" -> {
				if (ka == null || !Realm.inside(cx, cz)) return;
				ka.soldiers += 10 + ka.era * 4;
				Army army = KingdomAI.raiseArmy(r, ka, cx + 0.5f, cz + 0.5f, 10 + ka.era * 4);
				if (army != null && kb != null && ka.wars.contains(kb.id)) army.target = kb.id;
				r.log(1, x, z, "kingdoms.log.god_army", Chronicle.kingdom(ka));
				effect(level, x, z, true);
			}
			case "rebel" -> {
				if (ka == null) return;
				if (KingdomAI.incite(r, ka) == null) p.sendOverlayMessage(Component.translatable("kingdoms.god.no_rebels"));
			}
			case "observe" -> observe(p, level, x, z);
			case "return" -> leave(p);
			case "reset" -> {
				for (Settlement s : r.settlements.values()) for (CitizenEntity c : new ArrayList<>(Population.of(s.id))) c.discard();
				r.kingdoms.clear();
				r.settlements.clear();
				r.armies.clear();
				r.chronicle.clear();
				r.chronicleSent = 0;
				java.util.Arrays.fill(r.owner, (short) 0);
				java.util.Arrays.fill(r.claim, (short) 0);
				java.util.Arrays.fill(r.siege, (byte) 0);
				java.util.Arrays.fill(r.siegeBy, (short) 0);
				r.seeded = false;
				r.autoSeed = true;
				Sync.refresh(level.getServer(), r);
				return;
			}
			case "auto" -> r.autoSeed = a != 0;
			default -> {
				return;
			}
		}
		Sync.refresh(level.getServer(), r);
	}

	/** Lightning (or a meteor) on a place: hurts armies there, damages the town and its people. */
	static void strike(Realm r, ServerLevel level, int x, int z, boolean meteor) {
		int cx = r.cellX(x), cz = r.cellZ(z);
		for (Army a : r.armies.values()) {
			if (Math.hypot(a.x - cx - 0.5f, a.z - cz - 0.5f) < (meteor ? 2.5 : 1.5)) a.soldiers *= meteor ? 0.25f : 0.6f;
		}
		Settlement town = null;
		double best = meteor ? 40 : 24;
		for (Settlement s : r.settlements.values()) {
			double d = Math.hypot(s.x - x, s.z - z);
			if (d < best) {
				best = d;
				town = s;
			}
		}
		if (town != null) {
			Kingdom k = r.kingdoms.get(town.kingdom);
			if (k != null) {
				k.population *= meteor ? 0.8f : 0.95f;
				k.stability = Math.max(0, k.stability - (meteor ? 10 : 3));
			}
			List<Settlement.Building> hit = new ArrayList<>();
			for (Settlement.Building b : town.buildings) {
				if (b.ruined || b.type == com.dantemorrigan.kingdoms.sim.BuildingType.KEEP) continue;
				if (Math.hypot(b.px + 6 - x, b.pz + 6 - z) < (meteor ? 20 : 9)) hit.add(b);
			}
			int n = meteor ? 3 : 1;
			for (int i = 0; i < Math.min(n, hit.size()); i++) {
				Settlement.Building b = hit.get(i);
				Constructor.ruin(level, b);
				b.progress = 0;
				b.placed = 0;
				b.y = Integer.MIN_VALUE;
			}
			if (k != null) r.log(1, x, z, meteor ? "kingdoms.log.god_meteor" : "kingdoms.log.god_smite", Chronicle.name(town.name), Chronicle.kingdom(k));
		} else {
			r.log(0, x, z, meteor ? "kingdoms.log.god_meteor_wild" : "kingdoms.log.god_smite_wild");
		}
		if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) return;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		if (meteor) {
			level.explode(null, x + 0.5, y, z + 0.5, 6f, true, Level.ExplosionInteraction.TNT);
			level.sendParticles(ParticleTypes.LAVA, x + 0.5, y + 1, z + 0.5, 40, 2, 1, 2, 0.1);
		} else {
			for (int i = 0; i < 3; i++) {
				LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
				if (bolt == null) continue;
				int bx = x + level.getRandom().nextInt(9) - 4, bz = z + level.getRandom().nextInt(9) - 4;
				bolt.snapTo(bx + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz), bz + 0.5);
				level.addFreshEntity(bolt);
			}
		}
	}

	static void effect(ServerLevel level, int x, int z, boolean good) {
		if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) return;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		level.sendParticles(good ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SQUID_INK, x + 0.5, y + 2, z + 0.5, 80, 6, 3, 6, 0.05);
		level.sendParticles(good ? ParticleTypes.END_ROD : ParticleTypes.LARGE_SMOKE, x + 0.5, y + 6, z + 0.5, 40, 3, 6, 3, 0.02);
		level.playSound(null, new BlockPos(x, y, z), good ? SoundEvents.PLAYER_LEVELUP : SoundEvents.WITHER_AMBIENT, SoundSource.AMBIENT, 2f, good ? 0.6f : 1.2f);
	}

	/** Look down on a place as a spirit (spectator), remembering where to come back to. */
	static void observe(ServerPlayer p, ServerLevel level, int x, int z) {
		OBSERVERS.putIfAbsent(p.getUUID(), new Return(p.gameMode(), p.position(), p.getYRot(), p.getXRot()));
		p.setGameMode(GameType.SPECTATOR);
		int y = KingdomAI.heightAt(KingdomsMod.realm(), x, z) + 34;
		p.teleportTo(level, x + 0.5, y, z - 22.5, Set.<Relative>of(), 0f, 50f, false);
	}

	static void leave(ServerPlayer p) {
		Return ret = OBSERVERS.remove(p.getUUID());
		if (ret == null) return;
		ServerLevel level = p.level().getServer().overworld();
		p.teleportTo(level, ret.pos.x, ret.pos.y, ret.pos.z, Set.<Relative>of(), ret.yaw, ret.pitch, false);
		p.setGameMode(ret.mode);
	}

	public static boolean observing(ServerPlayer p) {
		return OBSERVERS.containsKey(p.getUUID());
	}

	private GodPowers() {}
}
