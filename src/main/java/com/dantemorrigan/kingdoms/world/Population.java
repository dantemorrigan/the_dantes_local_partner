package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.sim.BuildingType;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.KingdomAI;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Level of detail for people: a town near a player is filled with living citizens in proportion to its simulated
 * population; a town nobody is near is pure numbers again. Nothing about them is saved, the simulation is the truth.
 */
public final class Population {
	private static final int NEAR = 112, FAR = 170;
	private static final Map<Integer, List<CitizenEntity>> TOWNS = new HashMap<>();

	public static void tick(ServerLevel level, Realm realm) {
		if (level.getGameTime() % 40 != 0) return;
		List<ServerPlayer> players = level.players();
		for (Settlement s : realm.settlements.values()) {
			List<CitizenEntity> list = TOWNS.computeIfAbsent(s.id, q -> new ArrayList<>());
			list.removeIf(c -> c.isRemoved() || !c.isAlive());
			Kingdom k = realm.kingdoms.get(s.kingdom);
			boolean near = Constructor.near(players, s.x, s.z, NEAR);
			boolean far = !Constructor.near(players, s.x, s.z, FAR);
			boolean loaded = level.getChunkSource().hasChunk(s.x >> 4, s.z >> 4);
			if (far || !loaded || k == null || !k.alive || s.kingdom != (list.isEmpty() ? s.kingdom : list.get(0).kingdom)) {
				for (CitizenEntity c : list) c.discard();
				list.clear();
				if (far || k == null || !k.alive || !loaded) continue;
			}
			if (!near) continue;
			int[] want = wanted(realm, k, s);
			int[] have = new int[4];
			for (CitizenEntity c : list) have[c.role()]++;
			for (int role = 3; role >= 0; role--) {
				if (have[role] < want[role]) {
					CitizenEntity c = spawn(level, k, s, role);
					if (c != null) list.add(c);
					break; // one per pass: they trickle in
				}
				if (have[role] > want[role]) {
					for (CitizenEntity c : list) if (c.role() == role) {
						c.discard();
						break;
					}
				}
			}
		}
	}

	/** Peasants, builders, soldiers, king for a town. */
	static int[] wanted(Realm realm, Kingdom k, Settlement s) {
		int towns = Math.max(1, realm.settlementsOf(k.id).size());
		float share = k.population / towns;
		int done = 0;
		boolean building = false;
		for (Settlement.Building b : s.buildings) {
			if (b.done()) done++;
			else if (!b.ruined) building = true;
		}
		int peasants = Math.min(9, 1 + (int) (share / 8) + done / 4);
		int builders = building ? (s.capital ? 2 : 1) : 0;
		int soldiers = Math.min(7, (int) ((k.soldiers + KingdomAI.armySoldiers(realm, k) * 0.2f) / towns / 3) + (s.count(BuildingType.BARRACKS) > 0 ? 1 : 0));
		boolean keep = false;
		for (Settlement.Building b : s.buildings) if (b.type == BuildingType.KEEP && b.progress > 0.6f) keep = true;
		int king = s.capital && keep ? 1 : 0;
		return new int[]{peasants, builders, soldiers, king};
	}

	static CitizenEntity spawn(ServerLevel level, Kingdom k, Settlement s, int role) {
		BlockPos pos = spot(level, s, role);
		if (pos == null) return null;
		CitizenEntity c = KingdomsMod.CITIZEN.create(level, EntitySpawnReason.EVENT);
		if (c == null) return null;
		c.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
		c.setup(k, s, role);
		level.addFreshEntity(c);
		return c;
	}

	/** A walkable spot: by the keep for the king, in front of a random finished building for the rest. */
	static BlockPos spot(ServerLevel level, Settlement s, int role) {
		List<Settlement.Building> done = new ArrayList<>();
		for (Settlement.Building b : s.buildings) if (b.y != Integer.MIN_VALUE && b.placed > 144) done.add(b);
		int x, z;
		if (role == CitizenEntity.KING || done.isEmpty()) {
			x = s.x;
			z = s.z;
		} else {
			Settlement.Building b = done.get(level.getRandom().nextInt(done.size()));
			// on the street ring of the plot
			int side = level.getRandom().nextInt(4);
			int t = level.getRandom().nextInt(Settlement.PLOT);
			x = b.px + (side == 0 ? 0 : side == 1 ? Settlement.PLOT - 1 : t);
			z = b.pz + (side == 2 ? 0 : side == 3 ? Settlement.PLOT - 1 : t);
		}
		if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) return null;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (role == CitizenEntity.KING) {
			// inside the keep, on the floor
			for (Settlement.Building b : s.buildings) if (b.type == BuildingType.KEEP && b.y != Integer.MIN_VALUE) return new BlockPos(s.x, b.y + 1, s.z + 1);
		}
		return new BlockPos(x, y, z);
	}

	public static void clear() {
		TOWNS.clear();
	}

	/** Every living citizen of a town (for god effects). */
	public static List<CitizenEntity> of(int settlement) {
		return TOWNS.getOrDefault(settlement, List.of());
	}

	private Population() {}
}
