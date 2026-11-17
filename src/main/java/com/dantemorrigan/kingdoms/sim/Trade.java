package com.dantemorrigan.kingdoms.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Real trade: kingdoms with a market notice what they have too much of and what they lack, find a partner at peace
 * with the opposite stock, load a caravan and send it along a real land route. At the far market the goods are
 * bartered at prices that follow scarcity; the exchange comes home and both sides profit. War on the road turns a
 * caravan back, or gets it plundered.
 */
public final class Trade {
	public static final float SPEED = 1.5f;

	static float stock(Kingdom k, int type) {
		return switch (type) {
			case 0 -> k.food;
			case 1 -> k.wood;
			case 2 -> k.stone;
			default -> k.iron;
		};
	}

	static void add(Kingdom k, int type, float v) {
		switch (type) {
			case 0 -> k.food = Math.max(0, k.food + v);
			case 1 -> k.wood = Math.max(0, k.wood + v);
			case 2 -> k.stone = Math.max(0, k.stone + v);
			default -> k.iron = Math.max(0, k.iron + v);
		}
	}

	/** What a kingdom wants to keep in stock of a good (grows with its people and its age). */
	static float need(Kingdom k, int type) {
		float base = switch (type) {
			case 0 -> 60 + k.population * 2.2f;
			case 1 -> 80 + k.cells * 0.8f;
			case 2 -> 60 + k.cells * 0.6f;
			default -> k.era >= 2 ? 40 + k.soldiers * 2 : 10;
		};
		return base;
	}

	/** Scarcity multiplier on the base price: dear when short, cheap when overflowing. */
	public static float price(Kingdom k, int type) {
		float ratio = stock(k, type) / Math.max(1f, need(k, type));
		return Caravan.PRICE[type] * Math.max(0.5f, Math.min(2.5f, 1.8f - ratio));
	}

	static boolean hasMarket(Realm r, Settlement s) {
		return s.kingdom != 0 && s.count(BuildingType.MARKET) > 0 && s.buildings.stream().anyMatch(b -> b.type == BuildingType.MARKET && b.done() && !b.ruined);
	}

	public static void day(Realm r) {
		for (Caravan c : new ArrayList<>(r.caravans.values())) move(r, c);
		for (Kingdom k : r.kingdoms.values()) {
			if (!k.alive || r.day % 8 != k.id % 8) continue;
			int active = 0;
			for (Caravan c : r.caravans.values()) if (c.kingdom == k.id) active++;
			if (active >= 1 + k.era / 2) continue;
			dispatch(r, k);
		}
	}

	static void dispatch(Realm r, Kingdom k) {
		// the most overflowing good and the most lacking one
		int give = -1, want = -1;
		float bestSurplus = 25, bestLack = 0;
		for (int t = 0; t < 4; t++) {
			float over = stock(k, t) - need(k, t) * 1.15f, lack = need(k, t) - stock(k, t);
			if (over > bestSurplus) {
				bestSurplus = over;
				give = t;
			}
			if (lack > bestLack) {
				bestLack = lack;
				want = t;
			}
		}
		if (give < 0 || want < 0 || give == want) return;
		Settlement from = null;
		for (Settlement s : r.settlementsOf(k.id)) if (hasMarket(r, s) && (from == null || s.capital)) from = s;
		if (from == null) return;
		Settlement best = null;
		float bestScore = 0;
		for (Settlement s : r.settlements.values()) {
			Kingdom o = r.kingdoms.get(s.kingdom);
			if (o == null || !o.alive || o == k || !hasMarket(r, s) || k.wars.contains(o.id) || k.relation(o.id) < -25) continue;
			if (stock(o, want) < need(o, want) * 0.7f || stock(o, give) > need(o, give) * 1.1f) continue;
			double dist = Math.hypot(s.x - from.x, s.z - from.z) / Realm.CELL;
			if (dist > 90) continue;
			float score = (float) (price(o, give) / Math.max(0.1f, price(k, give)) * 10 - dist * 0.08f) + k.relation(o.id) * 0.02f;
			if (score > bestScore) {
				bestScore = score;
				best = s;
			}
		}
		if (best == null) return;
		int[] path = route(r, r.cellX(from.x), r.cellZ(from.z), r.cellX(best.x), r.cellZ(best.z));
		if (path == null || path.length < 3) return;
		int amt = (int) Math.min(bestSurplus, 30 + k.era * 15);
		Caravan c = new Caravan(r.nextId++, k.id, from.id, best.id);
		c.path = path;
		c.giveType = give;
		c.giveAmt = amt;
		c.getType = want;
		add(k, give, -amt);
		r.caravans.put(c.id, c);
		Kingdom o = r.kingdoms.get(best.kingdom);
		r.log(0, from.x, from.z, "kingdoms.log.caravan", Chronicle.kingdom(k), Chronicle.tr("kingdoms.good." + Caravan.GOODS[give]), Chronicle.kingdom(o));
	}

	/** Breadth-first route over land cells (any owner), or null when the sea is in the way. */
	static int[] route(Realm r, int sx, int sz, int tx, int tz) {
		int n = Realm.N;
		if (!Realm.inside(sx, sz) || !Realm.inside(tx, tz)) return null;
		int[] prev = new int[n * n];
		java.util.Arrays.fill(prev, -2);
		ArrayDeque<Integer> q = new ArrayDeque<>();
		int s = Realm.idx(sx, sz), t = Realm.idx(tx, tz);
		prev[s] = -1;
		q.add(s);
		int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
		while (!q.isEmpty()) {
			int cur = q.poll();
			if (cur == t) break;
			int cx = cur % n, cz = cur / n;
			for (int d = 0; d < 4; d++) {
				int nx = cx + dx[d], nz = cz + dz[d];
				if (!Realm.inside(nx, nz)) continue;
				int ni = Realm.idx(nx, nz);
				if (prev[ni] != -2) continue;
				int cls = r.terrainAt(nx, nz);
				if (Terrain.water(cls) || cls == Terrain.MOUNTAIN && r.random.nextInt(3) != 0) continue;
				prev[ni] = cur;
				q.add(ni);
			}
		}
		if (prev[t] == -2) return null;
		List<Integer> rev = new ArrayList<>();
		for (int c = t; c != -1; c = prev[c]) rev.add(c);
		int[] path = new int[rev.size()];
		for (int i = 0; i < path.length; i++) path[i] = rev.get(path.length - 1 - i);
		return path;
	}

	static void move(Realm r, Caravan c) {
		Kingdom k = r.kingdoms.get(c.kingdom);
		Settlement dest = r.settlements.get(c.returning ? c.from : c.to);
		Settlement home = r.settlements.get(c.from);
		if (k == null || !k.alive || home == null || dest == null || home.kingdom != k.id) {
			lose(r, c, null);
			return;
		}
		Kingdom partner = r.kingdoms.get(r.settlements.get(c.to) == null ? 0 : r.settlements.get(c.to).kingdom);
		if (!c.returning && (partner == null || !partner.alive || k.wars.contains(partner.id))) {
			c.returning = true; // war or collapse at the destination: turn back, goods and all
			c.getAmt = 0;
		}
		// plundered by whoever we are at war with, whose land we are crossing
		if (c.at >= 0 && c.path.length > 0) {
			int cell = c.path[Math.min(c.path.length - 1, (int) c.at)];
			int holder = r.owner[cell];
			Kingdom h = r.kingdoms.get(holder);
			if (h != null && h.alive && h != k && k.wars.contains(h.id) && r.random.nextFloat() < 0.35f) {
				lose(r, c, h);
				return;
			}
		}
		float sp = c.observed ? SPEED * 0.16f : SPEED;
		c.at += c.returning ? -sp : sp;
		if (!c.returning && c.at >= c.path.length - 1) {
			c.at = c.path.length - 1;
			arrive(r, c, k, partner);
		} else if (c.returning && c.at <= 0) {
			home(r, c, k);
		}
	}

	/** Barter at the far market: our goods for theirs, at their scarcity prices. */
	static void arrive(Realm r, Caravan c, Kingdom k, Kingdom o) {
		float value = c.giveAmt * price(o, c.giveType);
		float theirs = price(o, c.getType);
		int got = (int) Math.min(stock(o, c.getType) * 0.5f, value * 0.9f / Math.max(0.05f, theirs));
		add(o, c.giveType, c.giveAmt);
		add(o, c.getType, -got);
		o.gold += value * 0.05f;
		c.getAmt = got;
		c.profit = value * 0.1f;
		c.returning = true;
		k.addRelation(o.id, 3);
		o.addRelation(k.id, 3);
	}

	static void home(Realm r, Caravan c, Kingdom k) {
		add(k, c.getType, c.getAmt);
		k.gold += c.profit;
		Settlement from = r.settlements.get(c.from);
		float v = c.getAmt * Caravan.PRICE[c.getType];
		if (v >= 40 && from != null) {
			Kingdom o = r.kingdoms.get(r.settlements.get(c.to) == null ? 0 : r.settlements.get(c.to).kingdom);
			r.log(1, from.x, from.z, "kingdoms.log.trade_done", Chronicle.kingdom(k), String.valueOf(c.getAmt),
					Chronicle.tr("kingdoms.good." + Caravan.GOODS[c.getType]), o == null ? Chronicle.tr("kingdoms.word.time") : Chronicle.kingdom(o));
		}
		r.caravans.remove(c.id);
	}

	static void lose(Realm r, Caravan c, Kingdom robber) {
		Kingdom k = r.kingdoms.get(c.kingdom);
		if (robber != null && k != null) {
			add(robber, c.returning ? c.getType : c.giveType, c.returning ? c.getAmt : c.giveAmt);
			int cell = c.path[Math.min(c.path.length - 1, Math.max(0, (int) c.at))];
			r.log(1, r.blockX(cell % Realm.N), r.blockZ(cell / Realm.N), "kingdoms.log.caravan_raided", Chronicle.kingdom(robber), Chronicle.kingdom(k));
			k.addRelation(robber.id, -8);
		}
		r.caravans.remove(c.id);
	}

	private Trade() {}
}
