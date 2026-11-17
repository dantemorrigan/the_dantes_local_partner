package com.dantemorrigan.kingdoms.client;

import com.dantemorrigan.kingdoms.net.KNet;
import com.dantemorrigan.kingdoms.sim.Chronicle;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Names;
import com.dantemorrigan.kingdoms.sim.Realm;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the client knows of the realm, plus everything needed to animate it: for every cell the previous owner and
 * the moment it changed hands (borders slide from one to the other), and the previous position of every army.
 */
public final class ClientRealm {
	public static final long BORDER_ANIM_MS = 1800;

	public static int n = Realm.N;
	public static int originX, originZ;
	public static short[] owner = new short[n * n], prevOwner = new short[n * n], claim = new short[n * n], siegeBy = new short[n * n];
	public static byte[] siege = new byte[n * n];
	public static long[] changed = new long[n * n];
	public static boolean hasState;
	public static boolean god;
	public static long day;
	public static int speed = 1;
	/** Bumped whenever ownership changes: the political layer is redrawn. */
	public static int version;
	public static long lastChange;

	public static final Map<Integer, KView> kingdoms = new LinkedHashMap<>();
	public static final List<TView> towns = new ArrayList<>();
	public static final Map<Integer, AView> armies = new HashMap<>();
	public static final List<Chronicle.Entry> news = new ArrayList<>();
	public static long newsVersion;

	// terrain
	public static int tSize;
	public static byte[] tCls = new byte[0], tHeight = new byte[0];
	public static int tRows;
	public static int terrainVersion;
	public static int terrainOriginX = Integer.MIN_VALUE, terrainOriginZ = Integer.MIN_VALUE;

	public static final class KView {
		public int id, dye, era, cells, king[], kingNumber, kingAge, capital;
		public int[] name = {0, 40};
		public boolean alive, rebel;
		public float know, knowNext, knowFrom, pop, food, wood, stone, iron, gold, soldiers, army, might, stability, agg, exp, dip, pie;
		public int[] wars = new int[0], allies = new int[0];
		public final Map<Integer, Float> rel = new HashMap<>();
		/** Label position (cell coordinates), recomputed on change. */
		public float lx, lz;
		public int labelSize;

		public int color() {
			return 0xFF000000 | Kingdom.DYE_RGB[Math.floorMod(dye, 16)];
		}

		public Component title() {
			return Component.translatable(rebel ? "kingdoms.fmt.rebels" : "kingdoms.fmt.kingdom", Names.component(name));
		}

		public Component shortName() {
			return Names.component(name);
		}

		public Component kingTitle() {
			return Component.translatable("kingdoms.fmt.king", Names.component(king == null ? new int[]{1, 2} : king), Names.roman(kingNumber));
		}

		public boolean atWar(int other) {
			for (int w : wars) if (w == other) return true;
			return false;
		}

		public boolean allied(int other) {
			for (int a : allies) if (a == other) return true;
			return false;
		}
	}

	public record TView(int id, int kingdom, int[] name, int x, int z, boolean capital, int[] types, int done, int building, boolean ruin) {
		public Component title() {
			return Names.component(name);
		}
	}

	public static final class AView {
		public int id, kingdom, target, fighting;
		public float x, z, px, pz, soldiers;
		public long at;

		public float rx(long now, long dayMs) {
			float t = Math.min(1f, (now - at) / (float) dayMs);
			return px + (x - px) * t;
		}

		public float rz(long now, long dayMs) {
			float t = Math.min(1f, (now - at) / (float) dayMs);
			return pz + (z - pz) * t;
		}
	}

	public static KView kingdom(int id) {
		return kingdoms.get(id);
	}

	public static int ownerAt(int cx, int cz) {
		return cx < 0 || cz < 0 || cx >= n || cz >= n ? 0 : owner[cz * n + cx];
	}

	public static float cellX(double blockX) {
		return (float) ((blockX - originX) / Realm.CELL);
	}

	public static float cellZ(double blockZ) {
		return (float) ((blockZ - originZ) / Realm.CELL);
	}

	public static long dayMs() {
		return speed <= 0 ? 1000 : 1000L / speed;
	}

	// ------------------------------------------------------------------ packets

	public static void land(KNet.Land p) {
		if (tSize != p.size() || terrainOriginX != p.originX() || terrainOriginZ != p.originZ()) {
			tSize = p.size();
			tCls = new byte[tSize * tSize];
			tHeight = new byte[tSize * tSize];
			tRows = 0;
			terrainOriginX = p.originX();
			terrainOriginZ = p.originZ();
		}
		System.arraycopy(p.cls(), 0, tCls, p.row() * tSize, p.cls().length);
		System.arraycopy(p.height(), 0, tHeight, p.row() * tSize, p.height().length);
		tRows = Math.max(tRows, p.row() + p.rows());
		terrainVersion++;
	}

	public static boolean needTerrain() {
		return tSize == 0 || tRows < tSize || terrainOriginX != originX || terrainOriginZ != originZ;
	}

	public static void state(CompoundTag d) {
		long now = System.currentTimeMillis();
		if (d.getBooleanOr("full", false)) {
			int nn = d.getIntOr("n", Realm.N);
			if (nn != n) {
				n = nn;
				owner = new short[n * n];
				prevOwner = new short[n * n];
				claim = new short[n * n];
				siegeBy = new short[n * n];
				siege = new byte[n * n];
				changed = new long[n * n];
			}
			originX = d.getIntOr("ox", 0);
			originZ = d.getIntOr("oz", 0);
			short[] fresh = new short[n * n];
			Realm.unpack(d.getIntArray("owner").orElse(new int[0]), fresh);
			for (int i = 0; i < fresh.length; i++) {
				if (hasState && fresh[i] != owner[i]) {
					prevOwner[i] = owner[i];
					changed[i] = now;
					lastChange = now;
				} else if (!hasState) {
					prevOwner[i] = fresh[i];
					changed[i] = 0;
				}
				owner[i] = fresh[i];
			}
			Realm.unpack(d.getIntArray("claim").orElse(new int[0]), claim);
			Realm.unpack(d.getIntArray("siegeBy").orElse(new int[0]), siegeBy);
			byte[] sg = d.getByteArray("siege").orElse(new byte[0]);
			System.arraycopy(sg, 0, siege, 0, Math.min(sg.length, siege.length));
			news.clear();
			hasState = true;
		} else {
			int[] idx = d.getIntArray("ci").orElse(new int[0]), own = d.getIntArray("co").orElse(new int[0]), sg = d.getIntArray("cs").orElse(new int[0]);
			for (int j = 0; j < idx.length; j++) {
				int i = idx[j];
				if (i < 0 || i >= owner.length) continue;
				short o = (short) (own[j] & 0xFFFF);
				if (o != owner[i]) {
					prevOwner[i] = owner[i];
					changed[i] = now;
					lastChange = now;
					owner[i] = o;
				}
				claim[i] = (short) ((own[j] >>> 16) & 0xFFFF);
				siegeBy[i] = (short) (sg[j] & 0xFFFF);
				siege[i] = (byte) ((sg[j] >>> 16) & 0xFF);
			}
		}
		god = d.getBooleanOr("god", god);
		day = d.getLongOr("day", day);
		speed = d.getIntOr("speed", speed);
		readKingdoms(d);
		readTowns(d);
		readArmies(d, now);
		for (Tag t : d.getListOrEmpty("news")) if (t instanceof CompoundTag c) news.add(Chronicle.Entry.load(c));
		while (news.size() > 120) news.remove(0);
		newsVersion++;
		version++;
		labels();
	}

	static void readKingdoms(CompoundTag d) {
		Map<Integer, KView> old = new HashMap<>(kingdoms);
		kingdoms.clear();
		for (Tag t : d.getListOrEmpty("kingdoms")) {
			if (!(t instanceof CompoundTag c)) continue;
			int id = c.getIntOr("id", 0);
			KView k = old.getOrDefault(id, new KView());
			k.id = id;
			k.name = c.getIntArray("name").orElse(k.name);
			k.dye = c.getIntOr("dye", 14);
			k.alive = c.getBooleanOr("alive", true);
			k.rebel = c.getBooleanOr("rebel", false);
			k.era = c.getIntOr("era", 0);
			k.know = c.getFloatOr("know", 0);
			k.knowNext = c.getFloatOr("knowNext", 1);
			k.knowFrom = c.getFloatOr("knowFrom", 0);
			k.pop = c.getFloatOr("pop", 0);
			k.food = c.getFloatOr("food", 0);
			k.wood = c.getFloatOr("wood", 0);
			k.stone = c.getFloatOr("stone", 0);
			k.iron = c.getFloatOr("iron", 0);
			k.gold = c.getFloatOr("gold", 0);
			k.soldiers = c.getFloatOr("sold", 0);
			k.army = c.getFloatOr("army", 0);
			k.might = c.getFloatOr("might", 0);
			k.stability = c.getFloatOr("stab", 0);
			k.cells = c.getIntOr("cells", 0);
			k.king = c.getIntArray("king").orElse(new int[]{1, 2});
			k.kingNumber = c.getIntOr("kn", 1);
			k.kingAge = c.getIntOr("ka", 30);
			k.capital = c.getIntOr("cap", 0);
			k.agg = c.getFloatOr("agg", 0);
			k.exp = c.getFloatOr("exp", 0);
			k.dip = c.getFloatOr("dip", 0);
			k.pie = c.getFloatOr("pie", 0);
			k.wars = c.getIntArray("wars").orElse(new int[0]);
			k.allies = c.getIntArray("allies").orElse(new int[0]);
			k.rel.clear();
			CompoundTag rel = c.getCompoundOrEmpty("rel");
			for (String key : rel.keySet()) {
				try {
					k.rel.put(Integer.parseInt(key), rel.getFloatOr(key, 0));
				} catch (NumberFormatException ignored) {}
			}
			kingdoms.put(id, k);
		}
	}

	static void readTowns(CompoundTag d) {
		towns.clear();
		for (Tag t : d.getListOrEmpty("towns")) {
			if (!(t instanceof CompoundTag c)) continue;
			towns.add(new TView(c.getIntOr("id", 0), c.getIntOr("k", 0), c.getIntArray("name").orElse(new int[]{0, 40}),
					c.getIntOr("x", 0), c.getIntOr("z", 0), c.getBooleanOr("cap", false), c.getIntArray("types").orElse(new int[0]),
					c.getIntOr("done", 0), c.getIntOr("building", 0), c.getBooleanOr("ruin", false)));
		}
	}

	static void readArmies(CompoundTag d, long now) {
		Map<Integer, AView> old = new HashMap<>(armies);
		armies.clear();
		for (Tag t : d.getListOrEmpty("armies")) {
			if (!(t instanceof CompoundTag c)) continue;
			int id = c.getIntOr("id", 0);
			AView a = old.get(id);
			if (a == null) {
				a = new AView();
				a.id = id;
				a.px = a.x = c.getFloatOr("x", 0);
				a.pz = a.z = c.getFloatOr("z", 0);
			} else {
				a.px = a.rx(now, dayMs());
				a.pz = a.rz(now, dayMs());
				a.x = c.getFloatOr("x", 0);
				a.z = c.getFloatOr("z", 0);
			}
			a.at = now;
			a.kingdom = c.getIntOr("k", 0);
			a.soldiers = c.getFloatOr("s", 0);
			a.target = c.getIntOr("tg", 0);
			a.fighting = c.getIntOr("f", 0);
			armies.put(id, a);
		}
	}

	/** Each kingdom's name goes at the center of its land, sized by its area. */
	static void labels() {
		Map<Integer, double[]> acc = new HashMap<>();
		for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			int o = owner[z * n + x];
			if (o == 0) continue;
			double[] a = acc.computeIfAbsent(o, q -> new double[3]);
			a[0] += x + 0.5;
			a[1] += z + 0.5;
			a[2]++;
		}
		for (KView k : kingdoms.values()) {
			double[] a = acc.get(k.id);
			if (a == null) {
				k.labelSize = 0;
				continue;
			}
			k.lx = (float) (a[0] / a[2]);
			k.lz = (float) (a[1] / a[2]);
			// keep the label on the kingdom's own land: snap to the nearest owned cell if the centroid is outside
			int cx = (int) k.lx, cz = (int) k.lz;
			if (ownerAt(cx, cz) != k.id) {
				double best = Double.MAX_VALUE;
				for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
					if (owner[z * n + x] != k.id) continue;
					double dd = (x + 0.5 - k.lx) * (x + 0.5 - k.lx) + (z + 0.5 - k.lz) * (z + 0.5 - k.lz);
					if (dd < best) {
						best = dd;
						cx = x;
						cz = z;
					}
				}
				k.lx = cx + 0.5f;
				k.lz = cz + 0.5f;
			}
			k.labelSize = (int) a[2];
		}
	}

	public static void reset() {
		hasState = false;
		kingdoms.clear();
		towns.clear();
		armies.clear();
		news.clear();
	}

	private ClientRealm() {}
}
