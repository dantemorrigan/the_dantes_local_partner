package com.dantemorrigan.kingdoms.net;

import com.dantemorrigan.kingdoms.GodPowers;
import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.sim.Army;
import com.dantemorrigan.kingdoms.sim.BuildingType;
import com.dantemorrigan.kingdoms.sim.Chronicle;
import com.dantemorrigan.kingdoms.sim.Era;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.KingdomAI;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import com.dantemorrigan.kingdoms.sim.Terrain;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Who is looking at the map, and what they get: a full picture on opening, then what changed each day. */
public final class Sync {
	private static final Map<UUID, Integer> WATCHERS = new HashMap<>();
	/** Terrain rows already sent per watcher (-1: not wanted). */
	private static final int ROWS_PER_PACKET = 32;

	public static void view(ServerPlayer player, boolean open, boolean terrain) {
		if (!open) {
			WATCHERS.remove(player.getUUID());
			return;
		}
		WATCHERS.put(player.getUUID(), terrain ? 0 : Integer.MAX_VALUE);
		Realm r = KingdomsMod.realm();
		if (r != null) KNet.send(player, new KNet.State(full(r, player)));
	}

	public static void clear() {
		WATCHERS.clear();
	}

	/** Every tick: stream terrain to whoever still needs it. */
	public static void tick(MinecraftServer server, Realm r) {
		if (WATCHERS.isEmpty() || r.terrain == null) return;
		for (Map.Entry<UUID, Integer> e : WATCHERS.entrySet()) {
			int row = e.getValue();
			Terrain t = r.terrain;
			if (row >= t.size) continue;
			int ready = t.ready ? t.size : t.rowsDone;
			if (row >= ready) continue;
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) continue;
			int rows = Math.min(ROWS_PER_PACKET, ready - row);
			int from = row * t.size, to = (row + rows) * t.size;
			KNet.send(p, new KNet.Land(t.size, r.originX, r.originZ, row, rows, Arrays.copyOfRange(t.cls, from, to), Arrays.copyOfRange(t.height, from, to)));
			e.setValue(row + rows);
		}
		WATCHERS.values().removeIf(v -> false);
	}

	/** After each simulated day: what changed, to every watcher. */
	public static void day(MinecraftServer server, Realm r) {
		if (WATCHERS.isEmpty()) {
			r.dirtyCells.clear();
			r.chronicleSent = r.chronicle.size();
			return;
		}
		CompoundTag d = new CompoundTag();
		d.putBoolean("full", false);
		int n = r.dirtyCells.cardinality();
		int[] idx = new int[n], own = new int[n], siege = new int[n];
		int j = 0;
		for (int i = r.dirtyCells.nextSetBit(0); i >= 0; i = r.dirtyCells.nextSetBit(i + 1)) {
			idx[j] = i;
			own[j] = (r.owner[i] & 0xFFFF) | (r.claim[i] & 0xFFFF) << 16;
			siege[j] = (r.siegeBy[i] & 0xFFFF) | (r.siege[i] & 0xFF) << 16;
			j++;
		}
		r.dirtyCells.clear();
		d.putIntArray("ci", idx);
		d.putIntArray("co", own);
		d.putIntArray("cs", siege);
		common(r, d);
		ListTag news = new ListTag();
		for (int i = r.chronicleSent; i < r.chronicle.size(); i++) news.add(r.chronicle.get(i).save());
		r.chronicleSent = r.chronicle.size();
		d.put("news", news);
		for (UUID id : WATCHERS.keySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			d.putBoolean("god", GodPowers.allowed(p));
			KNet.send(p, new KNet.State(d.copy()));
		}
	}

	static CompoundTag full(Realm r, ServerPlayer p) {
		CompoundTag d = new CompoundTag();
		d.putBoolean("full", true);
		d.putInt("ox", r.originX);
		d.putInt("oz", r.originZ);
		d.putInt("n", Realm.N);
		d.putIntArray("owner", Realm.pack(r.owner));
		d.putIntArray("claim", Realm.pack(r.claim));
		d.putIntArray("siegeBy", Realm.pack(r.siegeBy));
		d.putByteArray("siege", r.siege.clone());
		common(r, d);
		ListTag news = new ListTag();
		for (int i = Math.max(0, r.chronicle.size() - 80); i < r.chronicle.size(); i++) news.add(r.chronicle.get(i).save());
		d.put("news", news);
		d.putBoolean("god", GodPowers.allowed(p));
		d.putBoolean("terrainReady", r.terrain != null && r.terrain.ready);
		return d;
	}

	static void common(Realm r, CompoundTag d) {
		d.putLong("day", r.day);
		d.putInt("speed", r.speed);
		ListTag ks = new ListTag();
		for (Kingdom k : r.kingdoms.values()) {
			CompoundTag t = new CompoundTag();
			t.putInt("id", k.id);
			t.putIntArray("name", k.name);
			t.putInt("dye", k.dye);
			t.putBoolean("alive", k.alive);
			t.putBoolean("rebel", k.rebel);
			t.putInt("era", k.era);
			Era next = k.era().next();
			t.putFloat("know", k.knowledge);
			t.putFloat("knowNext", next == k.era() ? k.knowledge : next.knowledge);
			t.putFloat("knowFrom", k.era().knowledge);
			t.putFloat("pop", k.population);
			t.putFloat("food", k.food);
			t.putFloat("wood", k.wood);
			t.putFloat("stone", k.stone);
			t.putFloat("iron", k.iron);
			t.putFloat("gold", k.gold);
			t.putFloat("sold", k.soldiers);
			t.putFloat("army", KingdomAI.armySoldiers(r, k));
			t.putFloat("might", k.might() + KingdomAI.armySoldiers(r, k) * k.era().might);
			t.putFloat("stab", k.stability);
			t.putInt("cells", k.cells);
			t.putIntArray("king", k.king);
			t.putInt("kn", k.kingNumber);
			t.putInt("ka", (int) k.kingAge);
			t.putInt("cap", k.capital);
			t.putFloat("agg", k.aggression);
			t.putFloat("exp", k.expansion);
			t.putFloat("dip", k.diplomacy);
			t.putFloat("pie", k.piety);
			t.putIntArray("wars", k.wars.stream().mapToInt(Integer::intValue).toArray());
			t.putIntArray("allies", k.allies.stream().mapToInt(Integer::intValue).toArray());
			CompoundTag rel = new CompoundTag();
			k.relations.forEach((o, v) -> rel.putFloat(String.valueOf(o), v));
			t.put("rel", rel);
			ks.add(t);
		}
		d.put("kingdoms", ks);
		ListTag ss = new ListTag();
		for (Settlement s : r.settlements.values()) {
			CompoundTag t = new CompoundTag();
			t.putInt("id", s.id);
			t.putInt("k", s.kingdom);
			t.putIntArray("name", s.name);
			t.putInt("x", s.x);
			t.putInt("z", s.z);
			t.putBoolean("cap", s.capital);
			int[] counts = new int[BuildingType.values().length];
			int done = 0, building = 0;
			for (Settlement.Building b : s.buildings) {
				if (b.ruined) continue;
				if (b.done()) {
					counts[b.type.ordinal()]++;
					done++;
				} else building++;
			}
			t.putIntArray("types", counts);
			t.putInt("done", done);
			t.putInt("building", building);
			t.putBoolean("ruin", s.kingdom == 0);
			ss.add(t);
		}
		d.put("towns", ss);
		ListTag as = new ListTag();
		for (Army a : r.armies.values()) as.add(a.save());
		d.put("armies", as);
	}

	/** Full state to every watcher (after a divine act). */
	public static void refresh(MinecraftServer server, Realm r) {
		r.dirtyCells.clear();
		r.chronicleSent = r.chronicle.size();
		for (UUID id : WATCHERS.keySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) KNet.send(p, new KNet.State(full(r, p)));
		}
	}

	public static boolean watching() {
		return !WATCHERS.isEmpty();
	}

	private Sync() {}
}
