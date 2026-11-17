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
	private static final Map<Settlement.Building, List<CitizenEntity>> ARCHERS = new java.util.IdentityHashMap<>();

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
			fill(level, realm, s, k, list, false);
			archers(level, realm, s, k);
			tags(list, players);
			for (Settlement.Building bd : s.buildings) {
				List<CitizenEntity> posts = ARCHERS.get(bd);
				if (posts != null) tags(posts, players);
			}
		}
	}

	/** Bowmen on every finished tower, a guard on each side of every gate passage and bowmen on the gate towers. */
	static void archers(ServerLevel level, Realm realm, Settlement s, Kingdom k) {
		for (Settlement.Building b : s.buildings) {
			boolean tower = b.type == BuildingType.WALL_TOWER, gate = b.type == BuildingType.GATE;
			if (!tower && !gate) continue;
			List<CitizenEntity> list = ARCHERS.computeIfAbsent(b, q -> new ArrayList<>());
			list.removeIf(c -> c.isRemoved() || !c.isAlive());
			if (!b.done() || b.y == Integer.MIN_VALUE || Constructor.pending(realm, s, b) || b.ruined) continue;
			int want = tower ? (s.capital && k.era >= 2 ? 2 : 1) : 6;
			var rot = Constructor.rotation(s, b);
			int style = Math.min(4, b.style);
			while (list.size() < want) {
				int j = list.size();
				// local coordinates of the post, and the way the sentry faces (local direction dx, dz)
				int lx, lz, ly;
				int fdx = 0, fdz = 0;
				boolean archer;
				if (tower) {
					lx = 5;
					lz = 5;
					ly = 10 + 2 * style;
					archer = true;
				} else if (j < 2) { // bowmen on the two gate towers
					lx = j == 0 ? 2 : 9;
					lz = 5;
					ly = WallWorks.height(style) + 5;
					archer = true;
				} else { // two guards at each mouth of the passage
					int g = j - 2;
					lx = g % 2 == 0 ? 4 : 7;
					boolean front = g < 2;
					lz = front ? 1 : 10;
					ly = 1;
					fdz = front ? -1 : 1;
					archer = false;
				}
				CitizenEntity c = KingdomsMod.CITIZEN.create(level, EntitySpawnReason.EVENT);
				if (c == null) break;
				int[] t = Constructor.rotate(lx, lz, rot);
				double x = b.px + t[0] + 0.5, y = b.y + ly, z = b.pz + t[1] + 0.5;
				c.snapTo(x, y, z, 0f, 0f);
				c.setup(k, s, CitizenEntity.SOLDIER);
				if (archer) c.makeArcher();
				float yaw = 0;
				if (!archer) {
					int[] a0 = Constructor.rotate(lx, lz, rot), a1 = Constructor.rotate(lx + fdx, lz + fdz, rot);
					yaw = (float) Math.toDegrees(Math.atan2(-(a1[0] - a0[0]), a1[1] - a0[1]));
				}
				c.setPost(x, y, z, yaw);
				level.addFreshEntity(c);
				list.add(c);
			}
		}
	}

	/** Names are shown only for a king, a commander, or up close: a crowd must not hide the town behind its signs. */
	static void tags(List<CitizenEntity> list, List<ServerPlayer> players) {
		for (CitizenEntity c : list) {
			boolean show = c.role() == CitizenEntity.KING || c.commander;
			if (!show) for (ServerPlayer p : players) if (p.distanceToSqr(c) < 64) show = true;
			c.setCustomNameVisible(show);
		}
	}

	/** A town is lived in at once: everyone it should have, now (for a player who arrives by magic). */
	public static void populate(ServerLevel level, Realm realm, Settlement s) {
		Kingdom k = realm.kingdoms.get(s.kingdom);
		if (k == null || !k.alive || !level.getChunkSource().hasChunk(s.x >> 4, s.z >> 4)) return;
		List<CitizenEntity> list = TOWNS.computeIfAbsent(s.id, q -> new ArrayList<>());
		list.removeIf(c -> c.isRemoved() || !c.isAlive());
		for (int i = 0; i < 40; i++) if (!fill(level, realm, s, k, list, true)) break;
		archers(level, realm, s, k);
	}

	/** Brings the town's citizens toward the wanted numbers: one per pass, or all of them. True if someone was added. */
	static boolean fill(ServerLevel level, Realm realm, Settlement s, Kingdom k, List<CitizenEntity> list, boolean all) {
		int[] want = wanted(realm, k, s);
		int[] have = new int[5];
		for (CitizenEntity c : list) have[c.role()]++;
		have[CitizenEntity.MERCHANT] = (int) list.stream().filter(c -> c.role() == CitizenEntity.MERCHANT).count();
		boolean added = false;
		for (int role = 4; role >= 0; role--) {
			if (have[role] < want[role]) {
				CitizenEntity c = spawn(level, k, s, role);
				if (c != null) {
					list.add(c);
					added = true;
				}
				if (!all) return added;
				return added;
			}
			if (have[role] > want[role]) {
				for (CitizenEntity c : list) if (c.role() == role && c.caravanId == 0) {
					c.discard();
					break;
				}
			}
		}
		return added;
	}

	/** Peasants, builders, soldiers, king, market vendor for a town. */
	static int[] wanted(Realm realm, Kingdom k, Settlement s) {
		int towns = Math.max(1, realm.settlementsOf(k.id).size());
		float share = k.population / towns;
		int done = 0, going = 0;
		for (Settlement.Building b : s.buildings) {
			if (b.done()) done++;
			else if (!b.ruined) going++;
		}
		int peasants = Math.min(9, 1 + (int) (share / 8) + done / 4);
		if (s.wall > 0 && s.wallProgress < 1f) going += 2;
		int builders = going == 0 ? 0 : Math.min(5, 1 + going / 2 + (s.capital ? 1 : 0));
		int soldiers = Math.min(7, (int) ((k.soldiers + KingdomAI.armySoldiers(realm, k) * 0.2f) / towns / 3) + (s.count(BuildingType.BARRACKS) > 0 ? 1 : 0));
		boolean keep = false;
		for (Settlement.Building b : s.buildings) if (b.type == BuildingType.KEEP && b.progress > 0.6f) keep = true;
		int king = s.capital && keep ? 1 : 0;
		boolean market = false;
		for (Settlement.Building b : s.buildings) if (b.type == BuildingType.MARKET && b.done() && !b.ruined) market = true;
		return new int[]{peasants, builders, soldiers, king, market ? 1 : 0};
	}

	static CitizenEntity spawn(ServerLevel level, Kingdom k, Settlement s, int role) {
		BlockPos pos = spot(level, s, role);
		if (pos == null) return null;
		CitizenEntity c = KingdomsMod.CITIZEN.create(level, EntitySpawnReason.EVENT);
		if (c == null) return null;
		c.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
		c.setup(k, s, role);
		if (role == CitizenEntity.MERCHANT) c.stayAt(pos, 7);
		if (role == CitizenEntity.SOLDIER && k.era >= 2 && level.getRandom().nextInt(3) == 0) c.makeArcher();
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
		if (role == CitizenEntity.BUILDER) {
			for (Settlement.Building b : s.buildings) {
				if (!b.done() && !b.ruined && b.y != Integer.MIN_VALUE) {
					int bxx = b.px + (level.getRandom().nextBoolean() ? 0 : 11), bzz = b.pz + level.getRandom().nextInt(12);
					return new BlockPos(bxx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bxx, bzz), bzz);
				}
			}
		}
		if (role == CitizenEntity.MERCHANT) {
			for (Settlement.Building b : s.buildings) {
				if (b.type == BuildingType.MARKET && b.y != Integer.MIN_VALUE && b.placed > 144) {
					int mx = b.px + 6, mz = b.pz + 6;
					return new BlockPos(mx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mx, mz), mz);
				}
			}
		}
		if (role == CitizenEntity.KING) {
			// inside the keep, on the floor
			for (Settlement.Building b : s.buildings) if (b.type == BuildingType.KEEP && b.y != Integer.MIN_VALUE) return new BlockPos(s.x, b.y + 1, s.z + 1);
		}
		return new BlockPos(x, y, z);
	}

	public static void clear() {
		TOWNS.clear();
		ARCHERS.clear();
	}

	/** Every living citizen of a town (for god effects). */
	public static List<CitizenEntity> of(int settlement) {
		return TOWNS.getOrDefault(settlement, List.of());
	}

	private Population() {}
}
