package com.dantemorrigan.kingdoms.sim;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every decision of every kingdom, once per simulation day: economy, construction, growth, knowledge and eras,
 * expansion and new towns, diplomacy, wars, armies and sieges, successions and rebellions. No player is needed.
 */
public final class KingdomAI {
	public static final int START_KINGDOMS = 5;

	// ------------------------------------------------------------------ seeding & founding

	/** Places the first kingdoms on good land around the spawn. True once done. */
	public static boolean seed(Realm r) {
		if (r.terrain == null || !r.terrain.ready) return false;
		RandomSource rnd = r.random;
		List<int[]> placed = new ArrayList<>();
		int c = Realm.N / 2;
		for (int attempt = 0; attempt < 4000 && placed.size() < START_KINGDOMS; attempt++) {
			double ang = rnd.nextDouble() * Math.PI * 2;
			double dist = 6 + rnd.nextDouble() * 52;
			int cx = c + (int) Math.round(Math.cos(ang) * dist), cz = c + (int) Math.round(Math.sin(ang) * dist);
			if (!goodSite(r, cx, cz)) continue;
			boolean far = true;
			for (int[] p : placed) if (Math.hypot(p[0] - cx, p[1] - cz) < 24) far = false;
			if (!far) continue;
			placed.add(new int[]{cx, cz});
			found(r, cx, cz, null);
		}
		r.seeded = true;
		r.log(2, 0, 0, "kingdoms.log.dawn", String.valueOf(placed.size()));
		return true;
	}

	public static boolean goodSite(Realm r, int cx, int cz) {
		if (!Realm.inside(cx, cz) || r.ownerAt(cx, cz) != 0) return false;
		int land = 0;
		for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
			int t = r.terrainAt(cx + dx, cz + dz);
			if (r.land(cx + dx, cz + dz) && t != Terrain.MOUNTAIN) land++;
		}
		int t = r.terrainAt(cx, cz);
		return land >= 8 && t != Terrain.MOUNTAIN && t != Terrain.BEACH && t != Terrain.SWAMP;
	}

	public static Kingdom found(Realm r, int cx, int cz, Kingdom parent) {
		RandomSource rnd = r.random;
		Kingdom k = new Kingdom(r.nextId++);
		k.name = Names.place(rnd);
		k.dye = freeDye(r);
		k.aggression = 0.15f + rnd.nextFloat() * 0.8f;
		k.expansion = 0.3f + rnd.nextFloat() * 0.7f;
		k.diplomacy = rnd.nextFloat();
		k.piety = rnd.nextFloat();
		k.king = Names.person(rnd);
		k.kingAge = 20 + rnd.nextInt(25);
		k.founded = r.day;
		if (parent != null) {
			k.era = parent.era;
			k.knowledge = parent.knowledge * 0.8f;
			k.rebel = true;
			k.aggression = Math.max(0.6f, k.aggression);
		}
		r.kingdoms.put(k.id, k);
		Settlement s = newTown(r, k, cx, cz, true);
		k.capital = s.id;
		for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
			int x = cx + dx, z = cz + dz;
			if (!r.land(x, z)) continue;
			if (r.ownerAt(x, z) == 0 || (parent != null && r.ownerAt(x, z) == parent.id)) r.setOwner(x, z, k.id);
		}
		r.setOwner(cx, cz, k.id);
		if (parent == null) r.log(2, s.x, s.z, "kingdoms.log.founded", Chronicle.kingdom(k), Chronicle.king(k));
		return k;
	}

	static Settlement newTown(Realm r, Kingdom k, int cx, int cz, boolean capital) {
		Settlement s = new Settlement(r.nextId++, k.id, Names.place(r.random), r.blockX(cx), r.blockZ(cz), capital);
		int[] p = s.plot(0);
		s.buildings.add(new Settlement.Building(capital ? BuildingType.KEEP : BuildingType.TOWN_HALL, p[0], p[1], k.era));
		r.settlements.put(s.id, s);
		return s;
	}

	static int freeDye(Realm r) {
		Set<Integer> used = new HashSet<>();
		for (Kingdom k : r.kingdoms.values()) if (k.alive) used.add(k.dye);
		for (int d : Kingdom.DYE_ORDER) if (!used.contains(d)) return d;
		return Kingdom.DYE_ORDER[r.random.nextInt(12)];
	}

	// ------------------------------------------------------------------ the day

	public static void day(Realm r) {
		r.day++;
		List<Kingdom> alive = new ArrayList<>();
		for (Kingdom k : r.kingdoms.values()) if (k.alive) alive.add(k);
		Map<Integer, int[]> land = landStats(r);
		for (Kingdom k : alive) {
			economy(r, k, land.getOrDefault(k.id, new int[16]));
			construction(r, k);
			if (r.day % 3 == k.id % 3) expand(r, k);
			if (r.day % 12 == k.id % 12) newTowns(r, k);
			ruler(r, k);
		}
		if (r.day % 2 == 0) diplomacy(r, alive);
		Trade.day(r);
		armies(r);
		for (Kingdom k : alive) if (r.day % 5 == k.id % 5) rebellion(r, k);
		for (Kingdom k : alive) checkCollapse(r, k);
	}

	/** Count of owned cells per terrain class, per kingdom. */
	static Map<Integer, int[]> landStats(Realm r) {
		Map<Integer, int[]> m = new HashMap<>();
		for (int z = 0; z < Realm.N; z++) for (int x = 0; x < Realm.N; x++) {
			int o = r.owner[Realm.idx(x, z)];
			if (o == 0) continue;
			m.computeIfAbsent(o, q -> new int[16])[r.terrainAt(x, z) & 15]++;
		}
		return m;
	}

	// ------------------------------------------------------------------ economy

	static void economy(Realm r, Kingdom k, int[] land) {
		float food = 0, wood = 0, stone = 0, iron = 0, gold = 0, know = 0;
		int housing = 0, temples = 0;
		for (Settlement s : r.settlementsOf(k.id)) {
			for (Settlement.Building b : s.buildings) {
				if (!b.done() || b.ruined) continue;
				BuildingType t = b.type;
				food += t.food;
				wood += t.outWood;
				stone += t.outStone;
				iron += t.outIron;
				gold += t.outGold;
				know += t.knowledge;
				housing += t.housing;
				if (t == BuildingType.TEMPLE) temples++;
			}
		}
		// The land itself feeds and supplies the kingdom.
		int fertile = land[Terrain.PLAINS] + land[Terrain.FLOWER] + land[Terrain.SAVANNA] + land[Terrain.CHERRY];
		int woods = land[Terrain.FOREST] + land[Terrain.TAIGA] + land[Terrain.JUNGLE] + land[Terrain.SWAMP];
		int rocks = land[Terrain.MOUNTAIN] + land[Terrain.BADLANDS] + land[Terrain.SNOW];
		food += fertile * 0.04f + woods * 0.015f;
		wood += woods * 0.05f + fertile * 0.01f;
		stone += rocks * 0.05f + land[Terrain.DESERT] * 0.02f;
		iron += rocks * 0.012f;
		gold += land[Terrain.BADLANDS] * 0.03f + land[Terrain.DESERT] * 0.01f;
		// Taxes and learning grow with the people, shrink with unrest.
		float order = 0.4f + k.stability / 160f;
		gold += k.population * 0.012f * order;
		know += k.population * 0.018f * order;

		float eat = k.population * 0.06f + k.soldiers * 0.07f + armySoldiers(r, k) * 0.1f;
		int built = 0;
		for (Settlement s : r.settlementsOf(k.id)) built += s.buildings.size();
		float upkeep = built * 0.045f + k.cells * 0.012f;
		k.food += food - eat;
		k.wood += wood - k.population * 0.012f;
		k.stone += stone - k.cells * 0.004f;
		k.iron += iron - (k.soldiers + armySoldiers(r, k)) * 0.01f * k.era;
		k.gold += gold - (k.soldiers + armySoldiers(r, k)) * 0.035f - upkeep;
		k.knowledge += know;

		// People.
		float room = housing - k.population;
		if (k.food < 0) {
			k.population *= 0.985f;
			k.stability -= 0.8f;
			k.food = 0;
			if (r.day % 24 == 0) r.log(0, 0, 0, "kingdoms.log.famine", Chronicle.kingdom(k));
		} else if (room > 0) {
			k.population += Math.min(room, 0.15f + k.population * 0.012f * (k.food > 20 ? 1 : 0.4f));
		} else if (room < -2) {
			k.population += room * 0.01f;
		}
		k.population = Math.max(3, k.population);
		if (k.gold < 0) { // unpaid soldiers leave
			k.soldiers = Math.max(0, k.soldiers - 0.4f);
			k.gold = 0;
			k.stability -= 0.2f;
		}
		cap(k);

		// Soldiers.
		int barracks = countDone(r, k, BuildingType.BARRACKS);
		int smithies = countDone(r, k, BuildingType.SMITHY);
		float want = k.population * (0.06f + 0.18f * k.aggression) * (k.wars.isEmpty() ? 1f : 1.8f);
		float cap = barracks == 0 ? k.population * 0.08f : want;
		if (k.soldiers + armySoldiers(r, k) < cap && k.food > 5 && k.gold > 2) {
			float rate = 0.08f + barracks * 0.25f + smithies * 0.08f;
			k.soldiers += rate;
			k.food -= rate;
			k.gold -= rate * 0.6f;
			if (k.era >= Era.CASTLE.ordinal()) k.iron = Math.max(0, k.iron - rate * 0.3f);
		}

		// Stability.
		float target = 62 + temples * 7 + k.piety * 8 - k.wars.size() * 9 - k.cells / 45f - (k.rebel ? 5 : 0)
				+ (k.food > 30 ? 4 : -6) + Math.min(10, k.gold / 40f);
		k.stability += (target - k.stability) * 0.03f;
		k.stability = Mth.clamp(k.stability, 0, 100);

		// Knowledge and eras.
		Era next = k.era().next();
		if (next != k.era() && k.knowledge >= next.knowledge) {
			k.era = next.ordinal();
			Settlement cap2 = r.capitalOf(k);
			r.log(2, cap2 == null ? 0 : cap2.x, cap2 == null ? 0 : cap2.z, "kingdoms.log.era", Chronicle.kingdom(k), Chronicle.tr(next.key()));
		}
	}

	static void cap(Kingdom k) {
		float storage = 200 + k.population * 6;
		k.food = Math.min(k.food, storage);
		k.wood = Math.max(0, Math.min(k.wood, storage));
		k.stone = Math.max(0, Math.min(k.stone, storage));
		k.iron = Math.max(0, Math.min(k.iron, storage));
		k.gold = Math.min(k.gold, storage * 1.5f);
	}

	static int countDone(Realm r, Kingdom k, BuildingType t) {
		int c = 0;
		for (Settlement s : r.settlementsOf(k.id)) for (Settlement.Building b : s.buildings) if (b.type == t && b.done() && !b.ruined) c++;
		return c;
	}

	static int count(Realm r, Kingdom k, BuildingType t) {
		int c = 0;
		for (Settlement s : r.settlementsOf(k.id)) for (Settlement.Building b : s.buildings) if (b.type == t && !b.ruined) c++;
		return c;
	}

	public static float armySoldiers(Realm r, Kingdom k) {
		float s = 0;
		for (Army a : r.armies.values()) if (a.kingdom == k.id) s += a.soldiers;
		return s;
	}

	// ------------------------------------------------------------------ construction

	static void construction(Realm r, Kingdom k) {
		float pace = (0.55f + 0.45f * Math.min(1f, k.population / 40f)) * (0.6f + k.stability / 250f);
		for (Settlement s : r.settlementsOf(k.id)) {
			int active = 0;
			for (Settlement.Building b : s.buildings) {
				if (b.done() || b.ruined) continue;
				active++;
				boolean was = b.done();
				b.progress = Math.min(1f, b.progress + pace / b.type.days);
				if (!was && b.done() && (b.type == BuildingType.KEEP || b.type == BuildingType.TEMPLE || b.type == BuildingType.FACTORY
						|| b.type == BuildingType.LIBRARY || b.type == BuildingType.TOWN_HALL)) {
					r.log(1, b.px + 6, b.pz + 6, "kingdoms.log.built", Chronicle.kingdom(k), Chronicle.tr(b.type.key()), Chronicle.name(s.name));
				}
			}
			int slots = s.capital ? 2 + k.era / 2 : 1;
			if (active >= slots) continue;
			int limit = s.capital ? 10 + k.era * 7 : 5 + k.era * 4;
			if (s.buildings.size() >= limit) continue;
			BuildingType t = choose(r, k, s);
			if (t == null || !afford(r, k, t)) continue;
			int[] plot = nextPlot(r, s);
			if (plot == null) continue;
			pay(r, k, t);
			s.buildings.add(new Settlement.Building(t, plot[0], plot[1], k.era));
		}
	}

	/** Everything costs more the bigger the realm: 4% per building already standing. */
	static float costFactor(Realm r, Kingdom k) {
		int built = 0;
		for (Settlement s : r.settlementsOf(k.id)) built += s.buildings.size();
		return 1f + built * 0.04f;
	}

	static boolean afford(Realm r, Kingdom k, BuildingType t) {
		float f = costFactor(r, k);
		return k.wood >= t.wood * f && k.stone >= t.stone * f && k.iron >= t.iron * f && k.gold >= t.gold * f;
	}

	static void pay(Realm r, Kingdom k, BuildingType t) {
		float f = costFactor(r, k);
		k.wood -= t.wood * f;
		k.stone -= t.stone * f;
		k.iron -= t.iron * f;
		k.gold -= t.gold * f;
	}

	static int[] nextPlot(Realm r, Settlement s) {
		Terrain tr = r.terrain;
		int centerH = heightAt(r, s.x, s.z);
		for (int tries = 0; tries < 12; tries++) {
			int[] p = s.plot(s.nextPlot++);
			int mx = p[0] + Settlement.PLOT / 2, mz = p[1] + Settlement.PLOT / 2;
			if (Terrain.water(r.terrainAtBlock(mx, mz))) continue;
			if (tr != null && tr.ready && Math.abs(heightAt(r, mx, mz) - centerH) > 9) continue;
			boolean clash = false;
			for (Settlement o : r.settlements.values()) {
				if (o == s) continue;
				if (Math.abs(o.x - mx) < 40 && Math.abs(o.z - mz) < 40) clash = true;
			}
			if (clash) continue;
			return p;
		}
		return null;
	}

	public static int heightAt(Realm r, int x, int z) {
		Terrain t = r.terrain;
		if (t == null || !t.ready) return 64;
		int s = Mth.clamp(Math.floorDiv(x - r.originX, Terrain.SAMPLE), 0, t.size - 1);
		int q = Mth.clamp(Math.floorDiv(z - r.originZ, Terrain.SAMPLE), 0, t.size - 1);
		return t.heightAt(s, q);
	}

	static BuildingType choose(Realm r, Kingdom k, Settlement s) {
		Era era = k.era();
		int housing = 0;
		for (Settlement t : r.settlementsOf(k.id)) for (Settlement.Building b : t.buildings) if (!b.ruined) housing += b.type.housing;
		if (k.population > housing * 0.82f) return BuildingType.HOUSE;
		float foodNet = k.food;
		if (foodNet < 25 || count(r, k, BuildingType.FARM) * 30 < k.population) return BuildingType.FARM;
		if (s.count(BuildingType.LUMBER_CAMP) == 0 || k.wood < 25) return BuildingType.LUMBER_CAMP;
		if (s.count(BuildingType.QUARRY) == 0 || k.stone < 20) return BuildingType.QUARRY;
		List<BuildingType> wish = new ArrayList<>();
		if (era.ordinal() >= Era.FEUDAL.ordinal()) {
			if (s.capital && s.count(BuildingType.BARRACKS) < 1 + (k.aggression > 0.6f ? 1 : 0)) wish.add(BuildingType.BARRACKS);
			if (!k.wars.isEmpty() && s.count(BuildingType.BARRACKS) < 2) wish.add(0, BuildingType.BARRACKS);
			if (s.count(BuildingType.MINE) == 0) wish.add(BuildingType.MINE);
			if (s.count(BuildingType.MARKET) == 0) wish.add(BuildingType.MARKET);
			if (s.capital && s.count(BuildingType.SMITHY) == 0) wish.add(BuildingType.SMITHY);
			if (s.count(BuildingType.TEMPLE) == 0 && (k.stability < 60 || k.piety > 0.5f || s.capital)) wish.add(BuildingType.TEMPLE);
		}
		if (era.ordinal() >= Era.CASTLE.ordinal()) {
			if (s.capital && s.count(BuildingType.LIBRARY) == 0) wish.add(BuildingType.LIBRARY);
			if (s.count(BuildingType.WALL_TOWER) < (s.capital ? 4 : 2)) wish.add(BuildingType.WALL_TOWER);
		}
		if (era.ordinal() >= Era.RENAISSANCE.ordinal() && s.count(BuildingType.WORKSHOP) < (s.capital ? 2 : 1)) wish.add(BuildingType.WORKSHOP);
		if (era.ordinal() >= Era.INDUSTRIAL.ordinal() && s.count(BuildingType.FACTORY) < (s.capital ? 2 : 1)) wish.add(BuildingType.FACTORY);
		for (BuildingType t : wish) if (afford(r, k, t)) return t;
		BuildingType[] filler = {BuildingType.HOUSE, BuildingType.FARM, BuildingType.HOUSE, BuildingType.LUMBER_CAMP, BuildingType.QUARRY};
		return wish.isEmpty() ? filler[r.random.nextInt(filler.length)] : wish.get(0);
	}

	// ------------------------------------------------------------------ expansion

	static void expand(Realm r, Kingdom k) {
		int limit = 9 + (int) (k.population * 0.55f) + k.era * 18;
		if (k.cells >= limit) return;
		if (r.random.nextFloat() > 0.35f + k.expansion * 0.6f) return;
		float cost = 1.5f + k.cells * 0.03f;
		if (k.gold < cost) return;
		List<Settlement> towns = r.settlementsOf(k.id);
		if (towns.isEmpty()) return;
		int bestX = -1, bestZ = -1;
		float best = -1e9f;
		for (int z = 0; z < Realm.N; z++) for (int x = 0; x < Realm.N; x++) {
			if (r.owner[Realm.idx(x, z)] != 0 || !r.land(x, z)) continue;
			int adj = (r.ownerAt(x + 1, z) == k.id ? 1 : 0) + (r.ownerAt(x - 1, z) == k.id ? 1 : 0)
					+ (r.ownerAt(x, z + 1) == k.id ? 1 : 0) + (r.ownerAt(x, z - 1) == k.id ? 1 : 0);
			if (adj == 0) continue;
			float d = 1e9f;
			for (Settlement s : towns) d = Math.min(d, (float) Math.hypot(r.cellX(s.x) - x, r.cellZ(s.z) - z));
			float score = value(r.terrainAt(x, z)) + adj * 1.6f - d * 0.45f + r.random.nextFloat() * 1.5f;
			if (r.claim[Realm.idx(x, z)] == k.id) score += 3;
			if (score > best) {
				best = score;
				bestX = x;
				bestZ = z;
			}
		}
		if (bestX < 0) return;
		k.gold -= cost;
		r.setOwner(bestX, bestZ, k.id);
	}

	static float value(int cls) {
		return switch (cls) {
			case Terrain.PLAINS, Terrain.FLOWER, Terrain.CHERRY -> 3f;
			case Terrain.FOREST, Terrain.TAIGA, Terrain.SAVANNA -> 2.5f;
			case Terrain.MOUNTAIN, Terrain.BADLANDS -> 2f;
			case Terrain.JUNGLE, Terrain.SWAMP, Terrain.BEACH -> 1.2f;
			case Terrain.DESERT, Terrain.SNOW -> 0.8f;
			default -> -50f;
		};
	}

	static void newTowns(Realm r, Kingdom k) {
		List<Settlement> towns = r.settlementsOf(k.id);
		if (towns.isEmpty() || k.cells < 22 * towns.size() || towns.size() >= 2 + k.era * 2) return;
		if (k.wood < 40 || k.stone < 20 || k.gold < 20) return;
		int bx = -1, bz = -1;
		float best = -1e9f;
		for (int z = 0; z < Realm.N; z++) for (int x = 0; x < Realm.N; x++) {
			if (r.owner[Realm.idx(x, z)] != k.id || !goodTown(r, x, z, k.id)) continue;
			float d = 1e9f;
			for (Settlement s : r.settlements.values()) d = Math.min(d, (float) Math.hypot(r.cellX(s.x) - x, r.cellZ(s.z) - z));
			if (d < 9) continue;
			float score = value(r.terrainAt(x, z)) - Math.abs(d - 11) * 0.3f + r.random.nextFloat();
			if (score > best) {
				best = score;
				bx = x;
				bz = z;
			}
		}
		if (bx < 0) return;
		k.wood -= 40;
		k.stone -= 20;
		k.gold -= 20;
		Settlement s = newTown(r, k, bx, bz, false);
		r.log(1, s.x, s.z, "kingdoms.log.town", Chronicle.kingdom(k), Chronicle.name(s.name));
	}

	static boolean goodTown(Realm r, int cx, int cz, int kingdom) {
		int good = 0;
		for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
			if (r.land(cx + dx, cz + dz) && r.terrainAt(cx + dx, cz + dz) != Terrain.MOUNTAIN) good++;
		}
		return good >= 8 && r.terrainAt(cx, cz) != Terrain.MOUNTAIN;
	}

	// ------------------------------------------------------------------ rulers

	static void ruler(Realm r, Kingdom k) {
		k.kingAge += 1f / Realm.DAYS_PER_YEAR;
		float risk = k.kingAge > 50 ? (k.kingAge - 50) * 0.0009f : 0.00005f;
		if (r.random.nextFloat() >= risk) return;
		String old = Chronicle.king(k);
		if (r.random.nextFloat() < 0.6f) {
			k.kingNumber++;
		} else {
			k.king = Names.person(r.random);
			k.kingNumber = 1;
		}
		k.kingAge = 18 + r.random.nextInt(20);
		k.stability = Math.max(0, k.stability - 8);
		k.aggression = Mth.clamp(k.aggression + (r.random.nextFloat() - 0.5f) * 0.4f, 0.05f, 1f);
		k.diplomacy = Mth.clamp(k.diplomacy + (r.random.nextFloat() - 0.5f) * 0.4f, 0f, 1f);
		Settlement c = r.capitalOf(k);
		r.log(1, c == null ? 0 : c.x, c == null ? 0 : c.z, "kingdoms.log.succession", Chronicle.kingdom(k), old, Chronicle.king(k));
	}

	/** The king was killed in the world: the realm is shaken and a successor takes the throne at once. */
	public static void killKing(Realm r, Kingdom k) {
		String old = Chronicle.king(k);
		k.kingNumber = 1;
		k.king = Names.person(r.random);
		k.kingAge = 18 + r.random.nextInt(20);
		k.stability = Math.max(0, k.stability - 20);
		Settlement c = r.capitalOf(k);
		r.log(2, c == null ? 0 : c.x, c == null ? 0 : c.z, "kingdoms.log.king_slain", Chronicle.kingdom(k), old, Chronicle.king(k));
	}

	// ------------------------------------------------------------------ diplomacy

	static void diplomacy(Realm r, List<Kingdom> alive) {
		for (int i = 0; i < alive.size(); i++) {
			for (int j = i + 1; j < alive.size(); j++) {
				Kingdom a = alive.get(i), b = alive.get(j);
				boolean border = r.borders(a.id, b.id);
				float drift;
				if (a.wars.contains(b.id)) drift = -0.3f;
				else if (a.allies.contains(b.id)) drift = 0.25f;
				else if (border) drift = -0.35f * (a.aggression + b.aggression) + 0.2f * (a.diplomacy + b.diplomacy);
				else drift = (0 - a.relation(b.id)) * 0.01f + 0.05f * (a.diplomacy + b.diplomacy);
				int claimsA = claims(r, a.id, b.id), claimsB = claims(r, b.id, a.id);
				drift -= Math.min(2f, (claimsA + claimsB) * 0.05f);
				if (a.rebel || b.rebel) drift -= 0.2f;
				a.addRelation(b.id, drift + (r.random.nextFloat() - 0.5f) * 0.6f);
				b.addRelation(a.id, drift + (r.random.nextFloat() - 0.5f) * 0.6f);

				boolean atWar = a.wars.contains(b.id);
				long since = r.day - Math.max(a.lastTreaty.getOrDefault(b.id, -999L), 0L);
				if (!atWar) {
					if (!a.allies.contains(b.id) && a.relation(b.id) > 55 && b.relation(a.id) > 55 && r.random.nextFloat() < 0.02f * (a.diplomacy + b.diplomacy)) {
						ally(r, a, b);
					}
					if (border && since > 40) {
						tryWar(r, a, b);
						if (!a.wars.contains(b.id)) tryWar(r, b, a);
					}
				} else {
					float wa = a.weariness.merge(b.id, 0.35f, Float::sum), wb = b.weariness.merge(a.id, 0.35f, Float::sum);
					float threshold = 45 + (a.aggression + b.aggression) * 25;
					if (since > 30 && (wa > threshold || wb > threshold) && r.random.nextFloat() < 0.05f) peace(r, a, b);
				}
			}
		}
	}

	static int claims(Realm r, int claimant, int holder) {
		int c = 0;
		for (int i = 0; i < r.owner.length; i++) if (r.claim[i] == claimant && r.owner[i] == holder) c++;
		return c;
	}

	static void tryWar(Realm r, Kingdom a, Kingdom b) {
		boolean claim = claims(r, a.id, b.id) > 0;
		if (a.allies.contains(b.id) || a.stability < 25 || (a.relation(b.id) > (claim ? -10 : -22) && !(a.aggression > 0.7f && a.relation(b.id) < 5))) return;
		float mine = a.might() + armySoldiers(r, a) * a.era().might, theirs = b.might() + armySoldiers(r, b) * b.era().might;
		if (mine < theirs * (1.25f - a.aggression * 0.4f)) return;
		if (r.random.nextFloat() > 0.05f + a.aggression * 0.08f) return;
		declareWar(r, a, b, false);
	}

	public static void declareWar(Realm r, Kingdom a, Kingdom b, boolean divine) {
		if (a == b || a.wars.contains(b.id)) return;
		a.allies.remove(b.id);
		b.allies.remove(a.id);
		a.wars.add(b.id);
		b.wars.add(a.id);
		a.weariness.put(b.id, 0f);
		b.weariness.put(a.id, 0f);
		a.lastTreaty.put(b.id, r.day);
		b.lastTreaty.put(a.id, r.day);
		a.addRelation(b.id, -40);
		b.addRelation(a.id, -40);
		Settlement c = r.capitalOf(b);
		r.log(2, c == null ? 0 : c.x, c == null ? 0 : c.z, divine ? "kingdoms.log.war_divine" : "kingdoms.log.war", Chronicle.kingdom(a), Chronicle.kingdom(b));
		// Allies keep their word (mostly).
		for (int ally : new ArrayList<>(b.allies)) {
			Kingdom x = r.kingdoms.get(ally);
			if (x != null && x.alive && x != a && !x.wars.contains(a.id) && x.relation(a.id) < 30 && r.random.nextFloat() < 0.7f) {
				x.wars.add(a.id);
				a.wars.add(x.id);
				x.lastTreaty.put(a.id, r.day);
				a.lastTreaty.put(x.id, r.day);
				r.log(1, 0, 0, "kingdoms.log.ally_joins", Chronicle.kingdom(x), Chronicle.kingdom(b), Chronicle.kingdom(a));
			}
		}
	}

	public static void peace(Realm r, Kingdom a, Kingdom b) {
		if (!a.wars.contains(b.id)) return;
		a.wars.remove(b.id);
		b.wars.remove(a.id);
		a.weariness.remove(b.id);
		b.weariness.remove(a.id);
		a.lastTreaty.put(b.id, r.day);
		b.lastTreaty.put(a.id, r.day);
		a.relations.put(b.id, -15f);
		b.relations.put(a.id, -15f);
		for (int i = 0; i < r.owner.length; i++) {
			if ((r.siegeBy[i] == a.id && r.owner[i] == b.id) || (r.siegeBy[i] == b.id && r.owner[i] == a.id)) r.setSiege(i, 0, 0);
		}
		if (b.rebel && !a.rebel) b.rebel = false; // a peace treaty is recognition
		if (a.rebel && !b.rebel) a.rebel = false;
		r.log(2, 0, 0, "kingdoms.log.peace", Chronicle.kingdom(a), Chronicle.kingdom(b));
	}

	public static void ally(Realm r, Kingdom a, Kingdom b) {
		if (a == b) return;
		if (a.wars.contains(b.id)) peace(r, a, b);
		a.allies.add(b.id);
		b.allies.add(a.id);
		a.relations.put(b.id, Math.max(60f, a.relation(b.id)));
		b.relations.put(a.id, Math.max(60f, b.relation(a.id)));
		r.log(1, 0, 0, "kingdoms.log.alliance", Chronicle.kingdom(a), Chronicle.kingdom(b));
	}

	// ------------------------------------------------------------------ armies and war

	static void armies(Realm r) {
		for (Kingdom k : r.kingdoms.values()) {
			if (!k.alive) continue;
			int attack = 0, defence = 0, patrol = 0;
			for (Army a : r.armies.values()) {
				if (a.kingdom != k.id) continue;
				if (a.mission == 0) attack++;
				else if (a.mission == 2) defence++;
				else patrol++;
			}
			Settlement c = r.capitalOf(k);
			if (!k.wars.isEmpty()) {
				if (attack < 1 + k.era / 2 && k.soldiers >= 6 && c != null) {
					raiseArmy(r, k, r.cellX(c.x) + 0.5f, r.cellZ(c.z) + 0.5f, k.soldiers * 0.55f);
				}
				if (defence < 1 && k.soldiers >= 4) {
					Army threat = threat(r, k);
					if (threat != null) {
						Settlement near = nearestTown(r, k, threat.x, threat.z);
						Army d = near == null ? null : raiseArmy(r, k, r.cellX(near.x) + 0.5f, r.cellZ(near.z) + 0.5f, Math.max(3, k.soldiers * 0.4f));
						if (d != null) d.mission = 2;
					}
				}
			} else if (patrol < 2 && k.soldiers >= 5 && r.day % 7 == k.id % 7) {
				float[] post = borderPost(r, k, r.random.nextInt(64));
				if (post != null) {
					Army p = raiseArmy(r, k, post[0], post[1], Math.min(6, k.soldiers * 0.25f));
					if (p != null) p.mission = 1;
				}
			}
		}
		List<Army> list = new ArrayList<>(r.armies.values());
		for (Army a : list) {
			Kingdom k = r.kingdoms.get(a.kingdom);
			if (k == null || !k.alive || a.soldiers < 0.5f) {
				r.armies.remove(a.id);
				continue;
			}
			a.fighting = Math.max(0, a.fighting - 1);
			// Battles between hostile armies: arithmetic only where nobody watches; where soldiers really fight, they decide.
			for (Army o : list) {
				if (o == a || o.soldiers < 0.5f || !k.wars.contains(o.kingdom)) continue;
				if (Math.hypot(o.x - a.x, o.z - a.z) > 1.3) continue;
				Kingdom ok = r.kingdoms.get(o.kingdom);
				if (ok == null) continue;
				float damp = a.observed && o.observed ? 0.08f : a.observed || o.observed ? 0.4f : 1f;
				float hitA = o.soldiers * ok.era().might * 0.07f * damp, hitB = a.soldiers * k.era().might * 0.07f * damp;
				a.soldiers -= hitA / k.era().might;
				o.soldiers -= hitB / ok.era().might;
				a.fighting = o.fighting = 3;
				k.weariness.merge(o.kingdom, hitA * 0.4f, Float::sum);
				ok.weariness.merge(a.kingdom, hitB * 0.4f, Float::sum);
				if (o.soldiers < 0.5f) {
					r.log(1, r.blockX((int) o.x), r.blockZ((int) o.z), "kingdoms.log.army_crushed", Chronicle.kingdom(k), Chronicle.kingdom(ok));
				}
			}
			if (a.soldiers < 0.5f) continue;
			float pace = a.observed ? 0.55f : 1f;
			if (a.mission == 1) {
				if (!k.wars.isEmpty()) {
					a.mission = 0;
				} else {
					if ((a.tx == a.x && a.tz == a.z) || Math.hypot(a.tx - a.x, a.tz - a.z) < 0.4 || r.day % 6 == a.id % 6) {
						float[] post = borderPost(r, k, r.random.nextInt(64));
						if (post == null) {
							goHome(r, k, a);
							continue;
						}
						a.tx = post[0];
						a.tz = post[1];
					}
					march(a, 0.22f * pace);
					continue;
				}
			}
			if (a.mission == 2) {
				if (k.wars.isEmpty()) {
					goHome(r, k, a);
					continue;
				}
				Army threat = threat(r, k);
				if (threat == null) {
					goHome(r, k, a);
					continue;
				}
				a.tx = threat.x;
				a.tz = threat.z;
				march(a, 0.5f * pace);
				continue;
			}
			if (k.wars.isEmpty() || (a.target != 0 && !k.wars.contains(a.target))) {
				goHome(r, k, a);
				continue;
			}
			if (a.target == 0 || !k.wars.contains(a.target)) a.target = k.wars.iterator().next();
			if (r.day % 4 == a.id % 4 || (a.tx == a.x && a.tz == a.z)) chooseObjective(r, k, a);
			march(a, 0.45f * pace);
			siegeStep(r, k, a);
		}
		r.armies.values().removeIf(a -> a.soldiers < 0.5f);
		// Sieges without an army slowly fade.
		if (r.day % 3 == 0) {
			for (int i = 0; i < r.siege.length; i++) {
				if (r.siege[i] <= 0) continue;
				boolean present = false;
				int cx = i % Realm.N, cz = i / Realm.N;
				for (Army a : r.armies.values()) if (a.kingdom == r.siegeBy[i] && Math.abs(a.x - cx - 0.5f) < 1.5f && Math.abs(a.z - cz - 0.5f) < 1.5f) present = true;
				if (!present) r.setSiege(i, r.siegeBy[i], r.siege[i] - 6);
			}
		}
	}

	/** An enemy army close to one of this kingdom's towns. */
	static Army threat(Realm r, Kingdom k) {
		Army best = null;
		double bd = 13;
		for (Army e : r.armies.values()) {
			if (e.soldiers < 1 || !k.wars.contains(e.kingdom)) continue;
			for (Settlement s : r.settlementsOf(k.id)) {
				double d = Math.hypot(e.x - r.cellX(s.x) - 0.5, e.z - r.cellZ(s.z) - 0.5);
				if (d < bd) {
					bd = d;
					best = e;
				}
			}
		}
		return best;
	}

	static Settlement nearestTown(Realm r, Kingdom k, float x, float z) {
		Settlement best = null;
		double bd = Double.MAX_VALUE;
		for (Settlement s : r.settlementsOf(k.id)) {
			double d = Math.hypot(x - r.cellX(s.x), z - r.cellZ(s.z));
			if (d < bd) {
				bd = d;
				best = s;
			}
		}
		return best;
	}

	/** A cell on the kingdom's frontier (next to another kingdom's land), picked by a rolling index; null if it has no frontier. */
	static float[] borderPost(Realm r, Kingdom k, int pick) {
		List<int[]> posts = new ArrayList<>();
		for (int z = 0; z < Realm.N; z++) for (int x = 0; x < Realm.N; x++) {
			if (r.owner[Realm.idx(x, z)] != k.id) continue;
			int o = 0;
			for (int d = 0; d < 4 && o == 0; d++) {
				int w = r.ownerAt(x + (d == 0 ? 1 : d == 1 ? -1 : 0), z + (d == 2 ? 1 : d == 3 ? -1 : 0));
				if (w != 0 && w != k.id) o = w;
			}
			if (o != 0) posts.add(new int[]{x, z});
		}
		if (posts.isEmpty()) return null;
		int[] p = posts.get(pick % posts.size());
		return new float[]{p[0] + 0.5f, p[1] + 0.5f};
	}

	public static Army raiseArmy(Realm r, Kingdom k, float x, float z, float soldiers) {
		soldiers = Math.min(soldiers, k.soldiers);
		if (soldiers < 1) return null;
		k.soldiers -= soldiers;
		Army a = new Army(r.nextId++, k.id, x, z, soldiers);
		r.armies.put(a.id, a);
		return a;
	}

	static void goHome(Realm r, Kingdom k, Army a) {
		Settlement c = r.capitalOf(k);
		if (c == null) {
			k.soldiers += a.soldiers;
			a.soldiers = 0;
			return;
		}
		a.tx = r.cellX(c.x) + 0.5f;
		a.tz = r.cellZ(c.z) + 0.5f;
		march(a, 0.6f);
		if (Math.hypot(a.tx - a.x, a.tz - a.z) < 0.6) {
			k.soldiers += a.soldiers;
			a.soldiers = 0;
		}
	}

	static void march(Army a, float speed) {
		float dx = a.tx - a.x, dz = a.tz - a.z;
		float d = (float) Math.hypot(dx, dz);
		if (d < 1e-3f) return;
		if (a.fighting > 0) speed *= 0.2f;
		float step = Math.min(d, speed);
		a.x += dx / d * step;
		a.z += dz / d * step;
	}

	/** Next enemy cell to take: an enemy town near the front if there is one, else the closest enemy cell on the border. */
	static void chooseObjective(Realm r, Kingdom k, Army a) {
		int enemy = a.target;
		float best = Float.MAX_VALUE;
		int bx = -1, bz = -1;
		for (int z = 0; z < Realm.N; z++) for (int x = 0; x < Realm.N; x++) {
			if (r.owner[Realm.idx(x, z)] != enemy) continue;
			boolean front = r.ownerAt(x + 1, z) == k.id || r.ownerAt(x - 1, z) == k.id || r.ownerAt(x, z + 1) == k.id || r.ownerAt(x, z - 1) == k.id;
			float d = (float) Math.hypot(x + 0.5f - a.x, z + 0.5f - a.z);
			if (!front) d += 6;
			if (r.settlementAtCell(x, z) != null) d -= 5;
			if (r.claim[Realm.idx(x, z)] == k.id) d -= 2;
			if (d < best) {
				best = d;
				bx = x;
				bz = z;
			}
		}
		if (bx < 0) {
			k.wars.remove(enemy); // nothing left to fight for
			Kingdom e = r.kingdoms.get(enemy);
			if (e != null) e.wars.remove(k.id);
			a.target = 0;
			return;
		}
		a.tx = bx + 0.5f;
		a.tz = bz + 0.5f;
	}

	static void siegeStep(Realm r, Kingdom k, Army a) {
		if (Math.hypot(a.tx - a.x, a.tz - a.z) > 0.75) return;
		int cx = (int) Math.floor(a.tx), cz = (int) Math.floor(a.tz);
		if (!Realm.inside(cx, cz)) return;
		int i = Realm.idx(cx, cz);
		int holder = r.owner[i];
		if (holder == k.id || holder == 0 || !k.wars.contains(holder)) {
			if (holder == 0 && r.land(cx, cz)) r.setOwner(cx, cz, k.id);
			chooseObjective(r, k, a);
			return;
		}
		Kingdom def = r.kingdoms.get(holder);
		Settlement town = r.settlementAtCell(cx, cz);
		float defense = 4 + def.might() / Math.max(4f, def.cells) * 6f;
		if (town != null) defense += 18 + def.might() * (town.capital ? 0.5f : 0.25f) + town.count(BuildingType.WALL_TOWER) * 10;
		float attack = a.soldiers * k.era().might;
		float rate = 40f * attack / (attack + defense);
		if (a.observed) rate *= Math.max(0.12f, a.pressure);
		a.soldiers -= defense * 0.008f;
		def.soldiers = Math.max(0, def.soldiers - attack * 0.002f);
		a.fighting = Math.max(a.fighting, 1);
		int now = (r.siegeBy[i] == k.id ? r.siege[i] : 0) + Math.max(2, Math.round(rate));
		if (now < 100) {
			r.setSiege(i, k.id, now);
			return;
		}
		r.setOwner(cx, cz, k.id);
		def.weariness.merge(k.id, 3f, Float::sum);
		if (town != null) captureTown(r, k, def, town);
		chooseObjective(r, k, a);
	}

	static void captureTown(Realm r, Kingdom winner, Kingdom loser, Settlement town) {
		town.kingdom = winner.id;
		boolean wasCapital = town.capital;
		town.capital = false;
		loser.population *= 0.75f;
		loser.stability -= wasCapital ? 25 : 10;
		loser.weariness.merge(winner.id, wasCapital ? 40f : 15f, Float::sum);
		winner.population += 4;
		r.log(2, town.x, town.z, wasCapital ? "kingdoms.log.capital_fell" : "kingdoms.log.town_taken",
				Chronicle.kingdom(winner), Chronicle.name(town.name), Chronicle.kingdom(loser));
		// The town's surroundings follow it.
		int tx = r.cellX(town.x), tz = r.cellZ(town.z);
		for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
			if (r.ownerAt(tx + dx, tz + dz) == loser.id) r.setOwner(tx + dx, tz + dz, winner.id);
		}
		if (wasCapital) {
			List<Settlement> left = r.settlementsOf(loser.id);
			if (!left.isEmpty()) {
				Settlement nc = left.get(0);
				nc.capital = true;
				loser.capital = nc.id;
			}
		}
	}

	// ------------------------------------------------------------------ rebellion & collapse

	static void rebellion(Realm r, Kingdom k) {
		if (k.stability > 18 || k.cells < 24) return;
		if (r.random.nextFloat() > 0.08f) return;
		incite(r, k);
	}

	/** A part of the kingdom breaks away: the town farthest from the capital and the land closest to it. */
	public static Kingdom incite(Realm r, Kingdom k) {
		Settlement cap = r.capitalOf(k);
		if (cap == null) return null;
		Settlement far = null;
		double fd = 0;
		for (Settlement s : r.settlementsOf(k.id)) {
			double d = Math.hypot(s.x - cap.x, s.z - cap.z);
			if (!s.capital && d > fd) {
				fd = d;
				far = s;
			}
		}
		int cx, cz;
		if (far != null) {
			cx = r.cellX(far.x);
			cz = r.cellZ(far.z);
		} else {
			// No second town: the farthest good land rises up.
			cx = -1;
			cz = -1;
			for (int z = 0; z < Realm.N; z++) for (int x = 0; x < Realm.N; x++) {
				if (r.owner[Realm.idx(x, z)] != k.id || !goodTown(r, x, z, k.id)) continue;
				double d = Math.hypot(x - r.cellX(cap.x), z - r.cellZ(cap.z));
				if (d > fd) {
					fd = d;
					cx = x;
					cz = z;
				}
			}
			if (cx < 0 || fd < 5) return null;
		}
		Kingdom rebels = new Kingdom(r.nextId++);
		rebels.name = Names.place(r.random);
		rebels.dye = freeDye(r);
		rebels.rebel = true;
		rebels.aggression = 0.7f;
		rebels.expansion = 0.6f;
		rebels.diplomacy = r.random.nextFloat();
		rebels.piety = r.random.nextFloat();
		rebels.king = Names.person(r.random);
		rebels.kingAge = 25 + r.random.nextInt(15);
		rebels.era = k.era;
		rebels.knowledge = k.knowledge * 0.8f;
		rebels.founded = r.day;
		r.kingdoms.put(rebels.id, rebels);
		Settlement seat;
		if (far != null) {
			seat = far;
			seat.kingdom = rebels.id;
			seat.capital = true;
		} else {
			seat = newTown(r, rebels, cx, cz, true);
		}
		rebels.capital = seat.id;
		// Land closer to the rebel seat than to any loyal town joins the rebels.
		List<Settlement> loyal = r.settlementsOf(k.id);
		int sx = r.cellX(seat.x), sz = r.cellZ(seat.z);
		for (int z = 0; z < Realm.N; z++) for (int x = 0; x < Realm.N; x++) {
			if (r.owner[Realm.idx(x, z)] != k.id) continue;
			double dr = Math.hypot(x - sx, z - sz), dl = Double.MAX_VALUE;
			for (Settlement s : loyal) dl = Math.min(dl, Math.hypot(x - r.cellX(s.x), z - r.cellZ(s.z)));
			if (dr < dl) r.setOwner(x, z, rebels.id);
		}
		float share = Math.max(0.2f, Math.min(0.5f, rebels.cells / (float) Math.max(1, rebels.cells + k.cells)));
		rebels.population = k.population * share;
		k.population -= rebels.population;
		rebels.soldiers = k.soldiers * share * 1.2f;
		k.soldiers -= k.soldiers * share;
		rebels.food = k.food * share;
		rebels.wood = k.wood * share;
		rebels.stone = k.stone * share;
		rebels.gold = k.gold * share;
		rebels.stability = 70;
		k.stability += 15;
		r.log(2, seat.x, seat.z, "kingdoms.log.rebellion", Chronicle.kingdom(rebels), Chronicle.kingdom(k), Chronicle.name(seat.name));
		declareWar(r, rebels, k, false);
		return rebels;
	}

	static void checkCollapse(Realm r, Kingdom k) {
		if (!k.alive) return;
		if (!r.settlementsOf(k.id).isEmpty()) {
			if (r.capitalOf(k) == null) {
				Settlement nc = r.settlementsOf(k.id).get(0);
				nc.capital = true;
				k.capital = nc.id;
			}
			return;
		}
		// No towns left: the kingdom is gone, its land goes to whoever holds the neighbouring towns.
		Kingdom heir = null;
		for (int w : k.wars) {
			Kingdom e = r.kingdoms.get(w);
			if (e != null && e.alive) heir = e;
		}
		r.log(2, 0, 0, "kingdoms.log.fallen", Chronicle.kingdom(k), heir == null ? Chronicle.tr("kingdoms.word.time") : Chronicle.kingdom(heir));
		destroy(r, k, heir);
	}

	/** Removes a kingdom. Its land passes to the heir (if any) or becomes wild; its towns become ruins. */
	public static void destroy(Realm r, Kingdom k, Kingdom heir) {
		k.alive = false;
		for (int z = 0; z < Realm.N; z++) for (int x = 0; x < Realm.N; x++) {
			if (r.owner[Realm.idx(x, z)] == k.id) r.setOwner(x, z, heir == null ? 0 : heir.id);
		}
		for (int i = 0; i < r.claim.length; i++) if (r.claim[i] == k.id) r.claim[i] = 0;
		for (Settlement s : r.settlementsOf(k.id)) {
			if (heir != null) {
				s.kingdom = heir.id;
				s.capital = false;
			} else {
				s.kingdom = 0;
				s.capital = false;
				for (Settlement.Building b : s.buildings) b.ruined = true;
			}
		}
		r.armies.values().removeIf(a -> a.kingdom == k.id);
		for (Kingdom o : r.kingdoms.values()) {
			o.wars.remove(k.id);
			o.allies.remove(k.id);
		}
		k.wars.clear();
		k.allies.clear();
	}

	private KingdomAI() {}
}
