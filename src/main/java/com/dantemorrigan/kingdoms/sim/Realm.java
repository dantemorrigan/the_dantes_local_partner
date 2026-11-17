package com.dantemorrigan.kingdoms.sim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole political world: a square grid of chunk-sized cells around the world spawn, who owns each of them,
 * the kingdoms, their towns and armies, and the chronicle. Pure data + rules; {@link KingdomAI} makes the decisions,
 * the world package turns it into blocks and people.
 */
public final class Realm {
	/** Cells per side; a cell is one chunk (16x16 blocks). 160 cells = 2560 blocks across. */
	public static final int N = 160;
	public static final int CELL = 16;
	public static final int DAYS_PER_YEAR = 48;

	/** Block coordinates of the north-west corner of cell (0,0). */
	public int originX, originZ;
	public final short[] owner = new short[N * N];
	/** A kingdom that lost this cell and wants it back (disputed land). */
	public final short[] claim = new short[N * N];
	/** Siege: who besieges this cell and how far (0..100). */
	public final short[] siegeBy = new short[N * N];
	public final byte[] siege = new byte[N * N];

	public final Map<Integer, Kingdom> kingdoms = new LinkedHashMap<>();
	public final Map<Integer, Settlement> settlements = new LinkedHashMap<>();
	public final Map<Integer, Army> armies = new LinkedHashMap<>();
	public final List<Chronicle.Entry> chronicle = new ArrayList<>();
	public int nextId = 1;
	public long day;
	public boolean seeded;
	/** Simulation days per second: 0 paused, 1, 2, 5, 10. */
	public int speed = 1;
	/** Bring kingdoms to life automatically on a new world. */
	public boolean autoSeed = true;

	public transient Terrain terrain;
	public final transient BitSet dirtyCells = new BitSet(N * N);
	public transient int chronicleSent;
	public final transient RandomSource random = RandomSource.create();

	// --- geometry ---

	public int cellX(int blockX) {
		return Math.floorDiv(blockX - originX, CELL);
	}

	public int cellZ(int blockZ) {
		return Math.floorDiv(blockZ - originZ, CELL);
	}

	public static boolean inside(int cx, int cz) {
		return cx >= 0 && cz >= 0 && cx < N && cz < N;
	}

	public static int idx(int cx, int cz) {
		return cz * N + cx;
	}

	public int blockX(int cx) {
		return originX + cx * CELL + CELL / 2;
	}

	public int blockZ(int cz) {
		return originZ + cz * CELL + CELL / 2;
	}

	public int ownerAt(int cx, int cz) {
		return inside(cx, cz) ? owner[idx(cx, cz)] : 0;
	}

	public int ownerAtBlock(int x, int z) {
		return ownerAt(cellX(x), cellZ(z));
	}

	/** Terrain class of a cell (its center sample); plains while the terrain is still being sampled. */
	public int terrainAt(int cx, int cz) {
		if (terrain == null || !inside(cx, cz)) return Terrain.PLAINS;
		int s = Math.min(terrain.size - 1, cx * 2 + 1), t = Math.min(terrain.size - 1, cz * 2 + 1);
		return terrain.cls[t * terrain.size + s];
	}

	public int terrainAtBlock(int x, int z) {
		if (terrain == null) return Terrain.PLAINS;
		int s = Math.floorDiv(x - originX, Terrain.SAMPLE), t = Math.floorDiv(z - originZ, Terrain.SAMPLE);
		if (s < 0 || t < 0 || s >= terrain.size || t >= terrain.size) return Terrain.PLAINS;
		return terrain.cls[t * terrain.size + s];
	}

	public boolean land(int cx, int cz) {
		return inside(cx, cz) && !Terrain.water(terrainAt(cx, cz));
	}

	public void setOwner(int cx, int cz, int kingdom) {
		int i = idx(cx, cz);
		if (owner[i] == kingdom) return;
		int old = owner[i];
		owner[i] = (short) kingdom;
		siege[i] = 0;
		siegeBy[i] = 0;
		if (claim[i] == kingdom) claim[i] = 0;
		if (old != 0 && kingdom != 0 && claim[i] == 0) claim[i] = (short) old;
		Kingdom o = kingdoms.get(old);
		if (o != null) o.cells--;
		Kingdom n = kingdoms.get(kingdom);
		if (n != null) n.cells++;
		dirtyCells.set(i);
	}

	public void setSiege(int i, int by, int value) {
		value = Math.max(0, Math.min(100, value));
		if (siege[i] == value && siegeBy[i] == by) return;
		siege[i] = (byte) value;
		siegeBy[i] = (short) (value == 0 ? 0 : by);
		dirtyCells.set(i);
	}

	public boolean borders(int a, int b) {
		for (int z = 0; z < N; z++) {
			for (int x = 0; x < N; x++) {
				if (owner[idx(x, z)] != a) continue;
				if (ownerAt(x + 1, z) == b || ownerAt(x - 1, z) == b || ownerAt(x, z + 1) == b || ownerAt(x, z - 1) == b) return true;
			}
		}
		return false;
	}

	public Settlement settlementAtCell(int cx, int cz) {
		for (Settlement s : settlements.values()) if (cellX(s.x) == cx && cellZ(s.z) == cz) return s;
		return null;
	}

	public List<Settlement> settlementsOf(int kingdom) {
		List<Settlement> list = new ArrayList<>();
		for (Settlement s : settlements.values()) if (s.kingdom == kingdom) list.add(s);
		return list;
	}

	public Settlement capitalOf(Kingdom k) {
		Settlement s = settlements.get(k.capital);
		return s != null && s.kingdom == k.id ? s : null;
	}

	public void recount() {
		for (Kingdom k : kingdoms.values()) k.cells = 0;
		for (short o : owner) {
			Kingdom k = kingdoms.get((int) o);
			if (k != null) k.cells++;
		}
	}

	// --- chronicle ---

	public void log(int level, int x, int z, String key, String... args) {
		chronicle.add(new Chronicle.Entry(day, key, List.of(args), level, x, z));
		if (chronicle.size() > 240) {
			int drop = chronicle.size() - 240;
			chronicle.subList(0, drop).clear();
			chronicleSent = Math.max(0, chronicleSent - drop);
		}
	}

	public int year() {
		return (int) (day / DAYS_PER_YEAR) + 1;
	}

	/** 0 spring, 1 summer, 2 autumn, 3 winter. */
	public int season() {
		return (int) (day % DAYS_PER_YEAR) * 4 / DAYS_PER_YEAR;
	}

	// --- persistence ---

	public CompoundTag save() {
		CompoundTag t = new CompoundTag();
		t.putInt("v", 1);
		t.putInt("ox", originX);
		t.putInt("oz", originZ);
		t.putIntArray("owner", pack(owner));
		t.putIntArray("claim", pack(claim));
		t.putIntArray("siegeBy", pack(siegeBy));
		t.putByteArray("siege", siege.clone());
		t.putInt("next", nextId);
		t.putLong("day", day);
		t.putBoolean("seeded", seeded);
		t.putInt("speed", speed);
		t.putBoolean("auto", autoSeed);
		ListTag ks = new ListTag();
		for (Kingdom k : kingdoms.values()) ks.add(k.save());
		t.put("kingdoms", ks);
		ListTag ss = new ListTag();
		for (Settlement s : settlements.values()) ss.add(s.save());
		t.put("towns", ss);
		ListTag as = new ListTag();
		for (Army a : armies.values()) as.add(a.save());
		t.put("armies", as);
		ListTag cs = new ListTag();
		for (Chronicle.Entry e : chronicle) cs.add(e.save());
		t.put("chronicle", cs);
		return t;
	}

	public static Realm load(CompoundTag t) {
		Realm r = new Realm();
		if (t.isEmpty()) return r;
		r.originX = t.getIntOr("ox", 0);
		r.originZ = t.getIntOr("oz", 0);
		unpack(t.getIntArray("owner").orElse(new int[0]), r.owner);
		unpack(t.getIntArray("claim").orElse(new int[0]), r.claim);
		unpack(t.getIntArray("siegeBy").orElse(new int[0]), r.siegeBy);
		byte[] sg = t.getByteArray("siege").orElse(new byte[0]);
		System.arraycopy(sg, 0, r.siege, 0, Math.min(sg.length, r.siege.length));
		r.nextId = t.getIntOr("next", 1);
		r.day = t.getLongOr("day", 0);
		r.seeded = t.getBooleanOr("seeded", false);
		r.speed = t.getIntOr("speed", 1);
		r.autoSeed = t.getBooleanOr("auto", true);
		for (Tag k : t.getListOrEmpty("kingdoms")) if (k instanceof CompoundTag c) {
			Kingdom kd = Kingdom.load(c);
			r.kingdoms.put(kd.id, kd);
		}
		for (Tag s : t.getListOrEmpty("towns")) if (s instanceof CompoundTag c) {
			Settlement st = Settlement.load(c);
			r.settlements.put(st.id, st);
		}
		for (Tag a : t.getListOrEmpty("armies")) if (a instanceof CompoundTag c) {
			Army ar = Army.load(c);
			r.armies.put(ar.id, ar);
		}
		for (Tag e : t.getListOrEmpty("chronicle")) if (e instanceof CompoundTag c) r.chronicle.add(Chronicle.Entry.load(c));
		r.chronicleSent = r.chronicle.size();
		r.recount();
		return r;
	}

	public boolean initialized() {
		return seeded || !kingdoms.isEmpty();
	}

	/** Two shorts per int. */
	public static int[] pack(short[] a) {
		int[] r = new int[(a.length + 1) / 2];
		for (int i = 0; i < a.length; i++) {
			int v = a[i] & 0xFFFF;
			r[i / 2] |= (i % 2 == 0) ? v : v << 16;
		}
		return r;
	}

	public static void unpack(int[] p, short[] into) {
		for (int i = 0; i < into.length && i / 2 < p.length; i++) {
			into[i] = (short) ((i % 2 == 0) ? p[i / 2] & 0xFFFF : (p[i / 2] >>> 16) & 0xFFFF);
		}
	}
}
