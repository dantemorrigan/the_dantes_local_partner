package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Road;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Roads in the world. Every road is a line of blocks across the land, 16 to a cell, laid in the style of its age:
 * a dirt track, a horse road of gravel with lamp posts, a cobbled stone road, a paved road, and finally a railway on
 * a gravel bed. Where the way meets water it crosses on a ford of planks, a timber bridge with rails and piles, a
 * stone bridge, or an iron-barred railway bridge. Like the rest of the realm, what the simulation says exists is
 * simply there when the chunks load; only the last stretch of a road still being laid is built before the player's
 * eyes, by a work crew.
 */
public final class RoadWorks {
	private static final Map<Integer, int[][]> POINTS = new HashMap<>();
	private static final Map<Integer, Integer> CURSOR = new HashMap<>();
	private static final Map<Integer, java.util.List<CitizenEntity>> CREWS = new HashMap<>();
	private static Set<Long> keys = new HashSet<>();
	private static int keysVersion = -1, keysRoads = -1;

	public static void clear() {
		POINTS.clear();
		CURSOR.clear();
		CREWS.clear();
		keys = new HashSet<>();
		keysVersion = -1;
	}

	/** Block positions along a road: from the centre of its first town through the gate, over land, to the gate of the last. */
	static int[][] points(Realm r, Road ro) {
		int[][] cached = POINTS.get(ro.id);
		if (cached != null) return cached;
		List<int[]> pts = new java.util.ArrayList<>();
		for (int c = 0; c < ro.cells.length; c++) {
			int cx = ro.cells[c] % Realm.N, cz = ro.cells[c] / Realm.N;
			int bx = r.originX + cx * Realm.CELL + Realm.CELL / 2, bz = r.originZ + cz * Realm.CELL + Realm.CELL / 2;
			if (c == 0) {
				pts.add(new int[]{bx, bz});
				continue;
			}
			int pcx = ro.cells[c - 1] % Realm.N, pcz = ro.cells[c - 1] / Realm.N;
			int dx = Integer.signum(cx - pcx), dz = Integer.signum(cz - pcz);
			for (int s = 1; s <= Realm.CELL; s++) pts.add(new int[]{bx - dx * (Realm.CELL - s), bz - dz * (Realm.CELL - s)});
		}
		Settlement sa = r.settlements.get(ro.a), sb = r.settlements.get(ro.b);
		if (sa != null) pts = approach(pts, sa);
		if (sb != null) {
			java.util.Collections.reverse(pts);
			pts = approach(pts, sb);
			java.util.Collections.reverse(pts);
		}
		int n = pts.size();
		int[] xs = new int[n], zs = new int[n];
		for (int i = 0; i < n; i++) {
			xs[i] = pts.get(i)[0];
			zs[i] = pts.get(i)[1];
		}
		int[][] p = {xs, zs};
		POINTS.put(ro.id, p);
		return p;
	}

	/** A road enters a town by its gate: centre, straight out through the gate, then over to where the road leaves the wall. */
	static List<int[]> approach(List<int[]> pts, Settlement s) {
		int R = s.wallR;
		int k = -1;
		for (int i = 0; i < pts.size(); i++) {
			if (Math.max(Math.abs(pts.get(i)[0] - s.x), Math.abs(pts.get(i)[1] - s.z)) > R + 2) {
				k = i;
				break;
			}
		}
		if (k < 0) return pts;
		int[] q = pts.get(k);
		int dx = q[0] - s.x, dz = q[1] - s.z;
		int gx = 0, gz = 0;
		if (Math.abs(dx) >= Math.abs(dz)) gx = dx >= 0 ? 1 : -1;
		else gz = dz >= 0 ? 1 : -1;
		List<int[]> out = new java.util.ArrayList<>();
		int x = s.x, z = s.z;
		out.add(new int[]{x, z});
		// out through the gate and a little beyond it
		for (int i = 0; i < R + 10; i++) {
			x += gx;
			z += gz;
			out.add(new int[]{x, z});
		}
		// over to the point where the road leaves the wall: first along the wall, then straight on
		while (gx != 0 ? z != q[1] : x != q[0]) {
			if (gx != 0) z += Integer.signum(q[1] - z);
			else x += Integer.signum(q[0] - x);
			out.add(new int[]{x, z});
		}
		while (gx != 0 ? x != q[0] : z != q[1]) {
			if (gx != 0) x += Integer.signum(q[0] - x);
			else z += Integer.signum(q[1] - z);
			out.add(new int[]{x, z});
		}
		for (int i = k + 1; i < pts.size(); i++) out.add(pts.get(i));
		return out;
	}

	/** True where a road, bridge or rail line runs (walls leave a gap there). */
	public static boolean isRoad(int x, int z) {
		Realm r = KingdomsMod.realm();
		if (r == null) return false;
		if (keysVersion != r.roadVersion || keysRoads != r.roads.size()) {
			Set<Long> k = new HashSet<>();
			for (Road ro : r.roads.values()) {
				int[][] p = points(r, ro);
				for (int i = 0; i < p[0].length; i++) {
					for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) k.add(((long) (p[0][i] + dx) << 32) ^ ((p[1][i] + dz) & 0xFFFFFFFFL));
				}
			}
			keys = k;
			keysVersion = r.roadVersion;
			keysRoads = r.roads.size();
		}
		return keys.contains(((long) x << 32) ^ (z & 0xFFFFFFFFL));
	}

	static boolean crewNear(Road ro, int x, int z) {
		for (CitizenEntity c : CREWS.getOrDefault(ro.id, List.of())) {
			if (c.isAlive() && Math.abs(c.getX() - x) < 26 && Math.abs(c.getZ() - z) < 26) return true;
		}
		return false;
	}

	public static void tick(ServerLevel level, Realm realm) {
		List<ServerPlayer> players = level.players();
		int budget = 300;
		for (Road ro : realm.roads.values()) {
			if (budget <= 0) break;
			if (ro.progress <= 0) continue;
			int[][] p = points(realm, ro);
			int len = p[0].length;
			int target = Math.min(len, (int) (ro.progress * len));
			if (target <= 0) continue;
			boolean fresh = ro.progress < 1f || realm.day - ro.doneDay <= 1;
			int front = Math.max(0, target - 1);
			boolean frontWatched = fresh && Constructor.near(players, p[0][front], p[1][front], 110);
			crew(level, realm, ro, p, front, frontWatched);
			int cur = CURSOR.getOrDefault(ro.id, 0);
			int scanned = 0, laid = 0;
			while (scanned < len && laid < 20 && budget > 0) {
				int i = (cur + scanned) % len;
				scanned++;
				if (i >= target || ro.bit(i)) continue;
				int x = p[0][i], z = p[1][i];
				if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
				boolean live = fresh && i >= target - 20 && Constructor.near(players, x, z, 80);
				if (live && !crewNear(ro, x, z)) continue;
				lay(level, ro, p, i, live);
				ro.setBit(i);
				laid++;
				budget -= 6;
			}
			CURSOR.put(ro.id, (cur + scanned) % len);
		}
	}

	/** A work crew follows the front of a road that is going up where somebody can see it. */
	static void crew(ServerLevel level, Realm realm, Road ro, int[][] p, int front, boolean watched) {
		List<CitizenEntity> list = CREWS.computeIfAbsent(ro.id, q -> new java.util.ArrayList<>());
		list.removeIf(c -> c.isRemoved() || !c.isAlive());
		if (!watched || ro.progress >= 1f) {
			if (!list.isEmpty() && ro.progress >= 1f) {
				for (CitizenEntity c : list) c.discard();
				list.clear();
			}
			return;
		}
		Kingdom k = realm.kingdoms.get(ro.kingdom);
		Settlement home = realm.settlements.get(ro.a);
		if (k == null || home == null) return;
		int fx = p[0][front], fz = p[1][front];
		if (!level.getChunkSource().hasChunk(fx >> 4, fz >> 4)) return;
		while (list.size() < 3) {
			CitizenEntity c = KingdomsMod.CITIZEN.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
			if (c == null) break;
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, fz);
			c.snapTo(fx + 0.5 + list.size(), y, fz + 0.5, 0f, 0f);
			c.setup(k, home, CitizenEntity.BUILDER);
			c.clearHome();
			level.addFreshEntity(c);
			list.add(c);
		}
		for (CitizenEntity c : list) c.setSite(fx, fz);
	}

	/** Everything the simulation says stands is put in the world now (the player is arriving). */
	public static void flush(ServerLevel level, Realm realm, int x, int z, int radius) {
		for (Road ro : realm.roads.values()) {
			int[][] p = points(realm, ro);
			int target = Math.min(p[0].length, (int) (ro.progress * p[0].length));
			for (int i = 0; i < target; i++) {
				if (ro.bit(i) || Math.abs(p[0][i] - x) > radius || Math.abs(p[1][i] - z) > radius) continue;
				if (!level.getChunkSource().hasChunk(p[0][i] >> 4, p[1][i] >> 4)) continue;
				lay(level, ro, p, i, false);
				ro.setBit(i);
			}
		}
	}

	// ------------------------------------------------------------------ one cross-section of road

	static final int GRAVEL_EDGE = 0;

	static void lay(ServerLevel level, Road ro, int[][] p, int i, boolean watched) {
		int n = p[0].length;
		int x = p[0][i], z = p[1][i];
		int j = Math.min(n - 1, i + 1), h = Math.max(0, i - 1);
		int dx = Integer.signum(p[0][j] - p[0][h]), dz = Integer.signum(p[1][j] - p[1][h]);
		if (dx != 0 && dz != 0) dz = 0;
		if (dx == 0 && dz == 0) dx = 1;
		int px = -dz, pz = dx;
		int lv = ro.level;
		int half = lv == 0 ? 0 : 1;
		for (int w = -half; w <= half; w++) {
			int bx = x + px * w, bz = z + pz * w;
			column(level, ro, p, i, bx, bz, w, half, dx, dz, watched);
		}
		// lamp posts along better roads
		if (lv >= 1 && i % 24 == 12) {
			int lx = x + px * (half + 1), lz = z + pz * (half + 1);
			int gy = WallWorks.groundY(level, lx, lz);
			if (gy != Integer.MIN_VALUE) {
				level.setBlock(new BlockPos(lx, gy + 1, lz), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
				level.setBlock(new BlockPos(lx, gy + 2, lz), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
				level.setBlock(new BlockPos(lx, gy + 3, lz), lv >= 2 ? Blocks.LANTERN.defaultBlockState() : Blocks.TORCH.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
	}

	static void column(ServerLevel level, Road ro, int[][] p, int i, int bx, int bz, int w, int half, int dx, int dz, boolean watched) {
		int lv = ro.level;
		int gy = WallWorks.groundY(level, bx, bz);
		boolean edge = Math.abs(w) == half && half > 0;
		boolean center = w == 0;
		if (gy == Integer.MIN_VALUE) {
			bridge(level, ro, p, i, bx, bz, edge, center, dx, dz);
			return;
		}
		BlockState surface = switch (lv) {
			case 0 -> Blocks.DIRT_PATH.defaultBlockState();
			case 1 -> center ? Blocks.GRAVEL.defaultBlockState() : Blocks.DIRT_PATH.defaultBlockState();
			case 2 -> center ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
			case 3 -> center ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
			default -> Blocks.GRAVEL.defaultBlockState();
		};
		level.setBlock(new BlockPos(bx, gy, bz), surface, Block.UPDATE_CLIENTS);
		int top = gy + 1;
		if (lv == 4 && center) {
			level.setBlock(new BlockPos(bx, top, bz), rail(level, p, i, dx, dz, gy), Block.UPDATE_CLIENTS);
			top++;
		}
		for (int dy = 0; dy < 3; dy++) { // clear the way: trees, bushes, overhangs
			BlockPos up = new BlockPos(bx, top + dy, bz);
			BlockState st = level.getBlockState(up);
			if (!st.isAir() && (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.canBeReplaced() || st.is(BlockTags.FLOWERS) || !st.getFluidState().isEmpty() || st.isSolid())) {
				level.setBlock(up, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		if (watched && level.getRandom().nextInt(4) == 0) {
			level.playSound(null, new BlockPos(bx, gy, bz), surface.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1f);
		}
	}

	/** Crossing water: a plank ford, a timber bridge with rails and piles, a stone bridge, an iron-barred railway bridge. */
	static void bridge(ServerLevel level, Road ro, int[][] p, int i, int bx, int bz, boolean edge, boolean center, int dx, int dz) {
		int lv = ro.level;
		int wy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) - 1; // top water block
		BlockState deck = switch (lv) {
			case 0, 1 -> Blocks.OAK_PLANKS.defaultBlockState();
			case 2 -> Blocks.STONE_BRICKS.defaultBlockState();
			case 3 -> Blocks.POLISHED_ANDESITE.defaultBlockState();
			default -> Blocks.SMOOTH_STONE.defaultBlockState();
		};
		level.setBlock(new BlockPos(bx, wy, bz), deck, Block.UPDATE_CLIENTS);
		if (lv >= 1 && edge) {
			BlockState rail = switch (lv) {
				case 1 -> Blocks.OAK_FENCE.defaultBlockState();
				case 2, 3 -> Blocks.STONE_BRICK_WALL.defaultBlockState();
				default -> Blocks.IRON_BARS.defaultBlockState();
			};
			level.setBlock(new BlockPos(bx, wy + 1, bz), rail, Block.UPDATE_ALL);
			if (lv >= 3 && i % 12 == 6) level.setBlock(new BlockPos(bx, wy + 2, bz), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		if (lv == 4 && center) level.setBlock(new BlockPos(bx, wy + 1, bz), rail(level, p, i, dx, dz, wy), Block.UPDATE_CLIENTS);
		for (int dy = 1; dy <= 3; dy++) { // free headroom over the water
			BlockPos up = new BlockPos(bx, wy + dy + (lv == 4 && center ? 1 : 0), bz);
			if (!level.getBlockState(up).isAir() && !(lv >= 1 && edge && dy == 1)) level.setBlock(up, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		if (lv >= 1 && edge && i % 6 == 0) { // piles down to the bed
			BlockState pile = lv == 1 ? Blocks.OAK_LOG.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
			BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos(bx, wy - 1, bz);
			for (int d = 0; d < 14; d++) {
				BlockState st = level.getBlockState(q);
				if (!(st.isAir() || !st.getFluidState().isEmpty())) break;
				level.setBlock(q, pile, Block.UPDATE_CLIENTS);
				q.move(0, -1, 0);
			}
		}
	}

	/** A rail laid along the road: straight, a curve at a corner, or a slope when the ground steps. */
	static BlockState rail(ServerLevel level, int[][] p, int i, int dx, int dz, int y) {
		int n = p[0].length;
		int ni = Math.min(n - 1, i + 1), pi = Math.max(0, i - 1);
		int ndx = Integer.signum(p[0][ni] - p[0][i]), ndz = Integer.signum(p[1][ni] - p[1][i]);
		int pdx = Integer.signum(p[0][i] - p[0][pi]), pdz = Integer.signum(p[1][i] - p[1][pi]);
		if (ndx == 0 && ndz == 0) {
			ndx = pdx;
			ndz = pdz;
		}
		if (pdx == 0 && pdz == 0) {
			pdx = ndx;
			pdz = ndz;
		}
		RailShape shape;
		if (ndx == pdx && ndz == pdz) {
			shape = ndx != 0 ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
			int ay = WallWorks.groundY(level, p[0][ni], p[1][ni]), by = WallWorks.groundY(level, p[0][pi], p[1][pi]);
			if (ay != Integer.MIN_VALUE && ay == y + 1) shape = ndx > 0 ? RailShape.ASCENDING_EAST : ndx < 0 ? RailShape.ASCENDING_WEST : ndz > 0 ? RailShape.ASCENDING_SOUTH : RailShape.ASCENDING_NORTH;
			else if (by != Integer.MIN_VALUE && by == y + 1) shape = pdx > 0 ? RailShape.ASCENDING_WEST : pdx < 0 ? RailShape.ASCENDING_EAST : pdz > 0 ? RailShape.ASCENDING_NORTH : RailShape.ASCENDING_SOUTH;
		} else {
			// the two directions the rail connects: back toward the previous point, and on to the next
			boolean north = pdz > 0 || ndz < 0, south = pdz < 0 || ndz > 0, east = pdx < 0 || ndx > 0, west = pdx > 0 || ndx < 0;
			if (south && east) shape = RailShape.SOUTH_EAST;
			else if (south && west) shape = RailShape.SOUTH_WEST;
			else if (north && west) shape = RailShape.NORTH_WEST;
			else shape = RailShape.NORTH_EAST;
		}
		return Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape);
	}

	private RoadWorks() {}
}
