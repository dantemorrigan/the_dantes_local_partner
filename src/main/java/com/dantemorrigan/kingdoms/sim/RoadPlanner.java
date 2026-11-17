package com.dantemorrigan.kingdoms.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Where kingdoms build roads: every town is tied to the capital, roads are rebuilt better in each new age (track,
 * horse road, stone road, paved road, railway), and friendly neighbours get a trade road between their capitals.
 * Routes go over land, and cross rivers and narrow seas with a ford or bridge when that is cheaper than going around.
 */
public final class RoadPlanner {
	static boolean connected(Realm r, int a, int b) {
		for (Road ro : r.roads.values()) if ((ro.a == a && ro.b == b) || (ro.a == b && ro.b == a)) return true;
		return false;
	}

	public static void plan(Realm r, Kingdom k) {
		List<Settlement> towns = r.settlementsOf(k.id);
		Settlement cap = r.capitalOf(k);
		if (cap == null || towns.size() < 1) return;
		int active = 0;
		for (Road ro : r.roads.values()) if (ro.kingdom == k.id && ro.progress < 1f) active++;
		if (active >= 1 + k.era / 2) return;
		int level = Math.min(4, k.era);
		// 1. every town to the capital
		for (Settlement t : towns) {
			if (t == cap || connected(r, cap.id, t.id)) continue;
			if (start(r, k, cap, t, level)) return;
		}
		// 2. the main roads of an age
		for (Road ro : r.roads.values()) {
			if (ro.kingdom != k.id || ro.progress < 1f || ro.level >= level) continue;
			float len = ro.cells.length;
			float f = 0.6f + 0.2f * ro.level;
			if (k.wood < len * 0.8f * f || k.stone < len * 0.6f * f * (1 + ro.level) || k.gold < len * 0.1f * f * ro.level) continue;
			k.wood -= len * 0.8f * f;
			k.stone -= len * 0.6f * f * (1 + ro.level);
			k.gold -= len * 0.1f * f * ro.level;
			ro.level++;
			ro.progress = 0.02f;
			ro.bits = new long[0];
			r.rebuildRoads();
			if (ro.level == 4) {
				Settlement sa = r.settlements.get(ro.a);
				r.log(1, sa == null ? 0 : sa.x, sa == null ? 0 : sa.z, "kingdoms.log.railway", Chronicle.kingdom(k));
			}
			return;
		}
		// 3. a trade road to a friendly neighbour's capital
		if (k.era >= 1) {
			for (Kingdom o : r.kingdoms.values()) {
				if (o == k || !o.alive || k.relation(o.id) < 15 || k.wars.contains(o.id)) continue;
				Settlement oc = r.capitalOf(o);
				if (oc == null || connected(r, cap.id, oc.id) || Math.hypot(oc.x - cap.x, oc.z - cap.z) / Realm.CELL > 45) continue;
				if (start(r, k, cap, oc, level)) return;
			}
		}
	}

	static boolean start(Realm r, Kingdom k, Settlement a, Settlement b, int level) {
		int[] path = route(r, k, r.cellX(a.x), r.cellZ(a.z), r.cellX(b.x), r.cellZ(b.z));
		if (path == null || path.length < 3) return false;
		int water = 0;
		for (int c : path) if (Terrain.water(r.terrainAt(c % Realm.N, c / Realm.N))) water++;
		float len = path.length;
		float f = 0.6f + 0.2f * level;
		float wood = len * 0.8f * f + water * 6, stone = len * 0.6f * f * (1 + level) + water * 4, gold = len * 0.1f * f * level;
		if (k.wood < wood || k.stone < stone || k.gold < gold) return false;
		k.wood -= wood;
		k.stone -= stone;
		k.gold -= gold;
		Road ro = new Road(r.nextId++, k.id, a.id, b.id, level);
		ro.cells = path;
		ro.progress = 0.001f;
		r.roads.put(ro.id, ro);
		Kingdom o = r.kingdoms.get(b.kingdom);
		r.log(0, a.x, a.z, "kingdoms.log.road", Chronicle.kingdom(k), Chronicle.name(b.name));
		return true;
	}

	/** Cheapest way over land (and across water, at a price); null when there is none worth building. */
	static int[] route(Realm r, Kingdom k, int sx, int sz, int tx, int tz) {
		int n = Realm.N;
		if (!Realm.inside(sx, sz) || !Realm.inside(tx, tz)) return null;
		float[] dist = new float[n * n];
		int[] prev = new int[n * n];
		Arrays.fill(dist, Float.MAX_VALUE);
		Arrays.fill(prev, -1);
		PriorityQueue<float[]> q = new PriorityQueue<>((p, o) -> Float.compare(p[0], o[0]));
		int s = Realm.idx(sx, sz), t = Realm.idx(tx, tz);
		dist[s] = 0;
		q.add(new float[]{0, s});
		int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
		while (!q.isEmpty()) {
			float[] cur = q.poll();
			int c = (int) cur[1];
			if (cur[0] > dist[c]) continue;
			if (c == t) break;
			int cx = c % n, cz = c / n;
			for (int d = 0; d < 4; d++) {
				int nx = cx + dx[d], nz = cz + dz[d];
				if (!Realm.inside(nx, nz)) continue;
				int ni = Realm.idx(nx, nz);
				float cost = cost(r, k, nx, nz);
				if (cost < 0) continue;
				float nd = dist[c] + cost;
				if (nd < dist[ni]) {
					dist[ni] = nd;
					prev[ni] = c;
					q.add(new float[]{nd, ni});
				}
			}
		}
		if (prev[t] == -1) return null;
		List<Integer> rev = new ArrayList<>();
		for (int c = t; c != -1; c = prev[c]) rev.add(c);
		int[] path = new int[rev.size()];
		for (int i = 0; i < path.length; i++) path[i] = rev.get(path.length - 1 - i);
		// no more than six cells of water in one go: that is a ford or a bridge, not a ferry line
		int run = 0;
		for (int c : path) {
			run = Terrain.water(r.terrainAt(c % n, c / n)) ? run + 1 : 0;
			if (run > 6) return null;
		}
		return path;
	}

	static float cost(Realm r, Kingdom k, int x, int z) {
		int cls = r.terrainAt(x, z);
		float c;
		switch (cls) {
			case Terrain.DEEP_OCEAN -> {
				return -1;
			}
			case Terrain.OCEAN -> c = 12;
			case Terrain.RIVER -> c = 6;
			case Terrain.MOUNTAIN -> c = 3;
			case Terrain.SWAMP -> c = 2;
			case Terrain.FOREST, Terrain.JUNGLE, Terrain.TAIGA -> c = 1.3f;
			default -> c = 1f;
		}
		int o = r.ownerAt(x, z);
		if (o != 0 && o != k.id) {
			if (k.wars.contains(o)) return -1;
			c += 1.5f;
		}
		if (r.roadLevel[Realm.idx(x, z)] > 0) c *= 0.4f; // use the roads that are already there
		return c;
	}

	private RoadPlanner() {}
}
