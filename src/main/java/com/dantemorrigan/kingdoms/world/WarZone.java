package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.sim.Army;
import com.dantemorrigan.kingdoms.sim.Caravan;
import com.dantemorrigan.kingdoms.sim.Chronicle;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.KingdomAI;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Armies and caravans in the flesh. Whenever a player is within sight of a field army, its soldiers stand in the world
 * (each one standing for a few of the simulated men when the army is large), march in column, deploy around the enemy
 * town and tighten the ring as the siege advances, fight the garrison and each other for real, and batter the
 * buildings. What happens to them decides the simulation: dead soldiers are gone from the army, and a siege only
 * progresses while soldiers stand at the walls. Caravans likewise walk as merchants you can trade with.
 */
public final class WarZone {
	private static final int RANGE = 170, MAX_PER_ARMY = 30, MAX_TOTAL = 120;
	private static final Map<Integer, List<CitizenEntity>> SQUADS = new HashMap<>();
	private static final Map<Integer, CitizenEntity> MERCHANTS = new HashMap<>();
	private static final Map<Integer, Boolean> DEPLOYED = new HashMap<>();

	public static void clear() {
		SQUADS.clear();
		MERCHANTS.clear();
		DEPLOYED.clear();
	}

	static int bx(Realm r, float cell) {
		return Math.round(r.originX + cell * Realm.CELL);
	}

	static int bz(Realm r, float cell) {
		return Math.round(r.originZ + cell * Realm.CELL);
	}

	public static void tick(ServerLevel level, Realm r) {
		tick(level, r, false);
	}

	/** {@code force}: right now (a player has just arrived by magic), not at the next beat. */
	public static void tick(ServerLevel level, Realm r, boolean force) {
		if (!force && level.getGameTime() % 10 != 0) return;
		List<ServerPlayer> players = level.players();
		// armies that no longer exist
		for (Iterator<Map.Entry<Integer, List<CitizenEntity>>> it = SQUADS.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			Army a = r.armies.get(e.getKey());
			if (a == null || a.soldiers < 0.5f) {
				for (CitizenEntity c : e.getValue()) c.discard();
				DEPLOYED.remove(e.getKey());
				it.remove();
			}
		}
		int total = 0;
		for (List<CitizenEntity> l : SQUADS.values()) total += l.size();
		for (Army a : new ArrayList<>(r.armies.values())) {
			Kingdom k = r.kingdoms.get(a.kingdom);
			if (k == null || !k.alive) continue;
			int x = bx(r, a.x), z = bz(r, a.z);
			List<CitizenEntity> squad = SQUADS.computeIfAbsent(a.id, q -> new ArrayList<>());
			squad.removeIf(c -> c.isRemoved() || !c.isAlive());
			if (!Constructor.near(players, x, z, RANGE)) {
				for (CitizenEntity c : squad) c.discard();
				squad.clear();
				DEPLOYED.remove(a.id);
				a.observed = false;
				a.pressure = 1f;
				continue;
			}
			if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
			if (!DEPLOYED.getOrDefault(a.id, false)) {
				float w = Math.max(1f, a.soldiers / MAX_PER_ARMY);
				int n = Math.min(MAX_PER_ARMY, Math.min(MAX_TOTAL - total, (int) Math.ceil(a.soldiers / w)));
				for (int i = 0; i < n; i++) {
					CitizenEntity c = spawn(level, r, k, a, x, z, i, w);
					if (c != null) {
						squad.add(c);
						total++;
					}
				}
				DEPLOYED.put(a.id, true);
			}
			a.observed = !squad.isEmpty();
			if (squad.isEmpty()) {
				a.soldiers = 0; // the whole force fell in the world
				continue;
			}
			deploy(level, r, a, k, squad, x, z);
		}
		caravans(level, r, players);
	}

	static CitizenEntity spawn(ServerLevel level, Realm r, Kingdom k, Army a, int x, int z, int i, float weight) {
		Settlement home = r.capitalOf(k);
		if (home == null) home = r.settlementsOf(k.id).isEmpty() ? null : r.settlementsOf(k.id).get(0);
		if (home == null) return null;
		int sx = x + (i % 6) * 2 - 5, sz = z + (i / 6) * 2 - 3;
		if (!level.getChunkSource().hasChunk(sx >> 4, sz >> 4)) return null;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
		CitizenEntity c = KingdomsMod.CITIZEN.create(level, EntitySpawnReason.EVENT);
		if (c == null) return null;
		c.snapTo(sx + 0.5, y, sz + 0.5, level.getRandom().nextFloat() * 360f, 0f);
		c.setup(k, home, CitizenEntity.SOLDIER);
		c.armyId = a.id;
		c.weight = weight;
		c.clearHome();
		if (k.era >= 2 && i % 3 == 2) c.makeArcher();
		level.addFreshEntity(c);
		return c;
	}

	/** Orders for one army: march in column, or ring the objective and tighten as the siege advances. */
	static void deploy(ServerLevel level, Realm r, Army a, Kingdom k, List<CitizenEntity> squad, int ax, int az) {
		int cx = (int) Math.floor(a.tx), cz = (int) Math.floor(a.tz);
		boolean sieging = a.mission == 0 && Realm.inside(cx, cz) && Math.hypot(a.tx - a.x, a.tz - a.z) < 0.9
				&& r.owner[Realm.idx(cx, cz)] != 0 && r.owner[Realm.idx(cx, cz)] != k.id && k.wars.contains((int) r.owner[Realm.idx(cx, cz)]);
		int n = squad.size();
		double centerX = ax, centerZ = az;
		double radius = 0;
		int siege = 0;
		Settlement town = null;
		if (sieging) {
			int i = Realm.idx(cx, cz);
			siege = r.siegeBy[i] == k.id ? r.siege[i] & 0xFF : 0;
			town = r.settlementAtCell(cx, cz);
			centerX = town != null ? town.x : bx(r, cx + 0.5f);
			centerZ = town != null ? town.z : bz(r, cz + 0.5f);
			radius = Math.max(6, 24 - 17 * siege / 100.0);
		}
		int at = 0;
		for (int i = 0; i < n; i++) {
			CitizenEntity c = squad.get(i);
			double sx, sz;
			if (sieging) {
				double ang = Math.PI * 2 * i / n + 0.35;
				sx = centerX + Math.cos(ang) * radius;
				sz = centerZ + Math.sin(ang) * radius;
				if (Math.hypot(c.getX() - centerX, c.getZ() - centerZ) < radius + 12) at++;
			} else {
				sx = centerX + (i % 5) * 2.2 - 4.4;
				sz = centerZ + (i / 5) * 2.2;
				at++;
			}
			if (!level.getChunkSource().hasChunk((int) sx >> 4, (int) sz >> 4)) continue;
			int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) sx, (int) sz);
			double dist = Math.hypot(c.getX() - sx, c.getZ() - sz);
			if (dist > 70 && c.getTarget() == null) {
				c.teleportTo(sx, sy, sz);
			} else if (c.getTarget() == null && (dist > 2.5 || c.getNavigation().isDone())) {
				if (dist > 1.5) c.getNavigation().moveTo(sx, sy, sz, a.fighting > 0 || sieging ? 1.15 : 1.0);
			}
		}
		a.pressure = sieging ? Math.min(1f, at / (float) n) : 1f;
		if (sieging && town != null && a.pressure > 0.3f && level.getGameTime() % 20 == 0) batter(level, r, town, k, squad, siege);
	}

	/** The besiegers break into the buildings of the town: blocks fly out of the walls, fires start. */
	static void batter(ServerLevel level, Realm r, Settlement town, Kingdom attacker, List<CitizenEntity> squad, int siege) {
		List<Settlement.Building> hit = new ArrayList<>();
		for (Settlement.Building b : town.buildings) if (b.y != Integer.MIN_VALUE && !b.ruined && b.placed > 144) hit.add(b);
		if (hit.isEmpty()) return;
		int strikes = 2 + siege / 25;
		for (int s = 0; s < strikes; s++) {
			Settlement.Building b = hit.get(level.getRandom().nextInt(hit.size()));
			int px = b.px + level.getRandom().nextInt(12), pz = b.pz + level.getRandom().nextInt(12);
			if (!level.getChunkSource().hasChunk(px >> 4, pz >> 4)) continue;
			for (int dy = 0; dy < 5; dy++) {
				BlockPos pos = new BlockPos(px, b.y + 1 + level.getRandom().nextInt(5), pz);
				BlockState st = level.getBlockState(pos);
				if (st.isAir() || st.is(Blocks.CHEST) || st.is(Blocks.BEDROCK)) continue;
				if (st.getDestroySpeed(level, pos) < 0) continue;
				level.setBlock(pos, level.getRandom().nextInt(6) == 0 ? Blocks.FIRE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				level.sendParticles(ParticleTypes.EXPLOSION, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0);
				level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.7f, 1.3f);
				break;
			}
		}
	}

	// ------------------------------------------------------------------ deaths

	public static void onDeath(CitizenEntity c) {
		Realm r = KingdomsMod.realm();
		if (r == null) return;
		Kingdom k = r.kingdoms.get(c.kingdom);
		if (k == null) return;
		if (c.armyId != 0) {
			Army a = r.armies.get(c.armyId);
			if (a != null) {
				a.soldiers = Math.max(0, a.soldiers - c.weight);
				a.dead += c.weight;
				k.weariness.merge(a.target, c.weight * 0.3f, Float::sum);
			}
		} else if (c.role() == CitizenEntity.SOLDIER) {
			k.soldiers = Math.max(0, k.soldiers - 1);
		} else if (c.role() == CitizenEntity.KING) {
			KingdomAI.killKing(r, k);
		} else if (c.role() == CitizenEntity.PEASANT || c.role() == CitizenEntity.BUILDER) {
			k.population = Math.max(3, k.population - 0.5f);
		} else if (c.role() == CitizenEntity.MERCHANT && c.caravanId != 0) {
			r.caravans.remove(c.caravanId); // the caravan is lost with its merchant
			MERCHANTS.remove(c.caravanId);
		}
	}

	// ------------------------------------------------------------------ caravans

	static void caravans(ServerLevel level, Realm r, List<ServerPlayer> players) {
		MERCHANTS.entrySet().removeIf(e -> {
			Caravan c = r.caravans.get(e.getKey());
			CitizenEntity m = e.getValue();
			if (c == null || m.isRemoved() || !m.isAlive() || !Constructor.near(players, bx(r, c.x(Realm.N)), bz(r, c.z(Realm.N)), RANGE)) {
				if (c != null) c.observed = false;
				m.discard();
				return true;
			}
			return false;
		});
		for (Caravan c : r.caravans.values()) {
			if (c.path.length == 0) continue;
			int x = bx(r, c.x(Realm.N)), z = bz(r, c.z(Realm.N));
			CitizenEntity m = MERCHANTS.get(c.id);
			if (m == null) {
				if (!Constructor.near(players, x, z, 110) || !level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
				Kingdom k = r.kingdoms.get(c.kingdom);
				Settlement home = r.settlements.get(c.from);
				if (k == null || home == null) continue;
				m = KingdomsMod.CITIZEN.create(level, EntitySpawnReason.EVENT);
				if (m == null) continue;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				m.snapTo(x + 0.5, y, z + 0.5, 0f, 0f);
				m.setup(k, home, CitizenEntity.MERCHANT);
				m.caravanId = c.id;
				m.setCustomName(net.minecraft.network.chat.Component.translatable("kingdoms.citizen.caravan", k.title(),
						net.minecraft.network.chat.Component.translatable("kingdoms.good." + Caravan.GOODS[c.returning ? c.getType : c.giveType])));
				m.setCustomNameVisible(true);
				level.addFreshEntity(m);
				MERCHANTS.put(c.id, m);
			}
			c.observed = true;
			if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			double dist = Math.hypot(m.getX() - x, m.getZ() - z);
			if (dist > 45) m.teleportTo(x + 0.5, y, z + 0.5);
			else if (dist > 2.5) m.getNavigation().moveTo(x + 0.5, y, z + 0.5, 1.0);
		}
	}

	private WarZone() {}
}
