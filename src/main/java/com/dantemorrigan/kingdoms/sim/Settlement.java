package com.dantemorrigan.kingdoms.sim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/** A town of a kingdom: a center (keep or town hall) and buildings on a grid of 12-block plots around it. */
public final class Settlement {
	public static final int PLOT = 12;

	public final int id;
	public int kingdom;
	public final int[] name;
	public final int x, z;
	public boolean capital;
	public final List<Building> buildings = new ArrayList<>();
	/** Next spiral index for a plot. */
	public int nextPlot = 1;
	public float garrison;
	/** Town wall: 0 none, else style (age) + 1. Built column by column around the town along a square ring. */
	public int wall;
	public float wallProgress;
	public long wallDoneDay;
	public long[] wallBits = new long[10];
	/** Half-size of the wall square in blocks: it encloses every plot the town can ever have (bigger for a capital). */
	public int wallR;

	public static int radiusFor(boolean capital) {
		return capital ? 68 : 56;
	}

	/** Columns along the wall ring. */
	public int wallLen() {
		return wallR * 8;
	}

	/** The farthest a plot's edge may be from the town centre: inside the wall, with room for a street. */
	public int plotLimit() {
		return wallR - 9;
	}

	/**
	 * Block position of wall column i, walking the square clockwise from the north-west corner; {@code off} steps
	 * inward from the outer face (0 outer, 1 the walkway behind it).
	 */
	public int[] wallPos(int i, int off) {
		int R = wallR, side = i / (R * 2), pos = i % (R * 2);
		return switch (side) {
			case 0 -> new int[]{x - R + pos, z - R + off};
			case 1 -> new int[]{x + R - off, z - R + pos};
			case 2 -> new int[]{x + R - pos, z + R - off};
			default -> new int[]{x - R + off, z + R - pos};
		};
	}

	public int[] wallPos(int i) {
		return wallPos(i, 0);
	}

	public boolean wallBit(int i) {
		return (wallBits[i >> 6] >>> (i & 63) & 1L) != 0;
	}

	public void setWallBit(int i) {
		wallBits[i >> 6] |= 1L << (i & 63);
	}

	/** The gate house takes a twelve-column stretch around the middle of each side: the wall leaves it free. */
	public boolean gate(int i) {
		int pos = i % (wallR * 2);
		return pos >= wallR - 6 && pos <= wallR + 5;
	}

	public int wallSide(int i) {
		return i / (wallR * 2);
	}

	public Settlement(int id, int kingdom, int[] name, int x, int z, boolean capital) {
		this.id = id;
		this.kingdom = kingdom;
		this.name = name;
		this.x = x;
		this.z = z;
		this.capital = capital;
		this.wallR = radiusFor(capital);
	}

	/** Plot corner (block coordinates) for spiral index i (0 = the center plot). */
	public int[] plot(int i) {
		int[] o = spiral(i);
		return new int[]{x - PLOT / 2 + o[0] * PLOT, z - PLOT / 2 + o[1] * PLOT};
	}

	/** Square spiral: 0 -> (0,0), then ring by ring. */
	public static int[] spiral(int n) {
		if (n == 0) return new int[]{0, 0};
		int k = (int) Math.ceil((Math.sqrt(n + 1) - 1) / 2);
		int t = 2 * k + 1, m = t * t;
		t -= 1;
		if (n >= m - t) return new int[]{k - (m - n), -k};
		m -= t;
		if (n >= m - t) return new int[]{-k, -k + (m - n)};
		m -= t;
		if (n >= m - t) return new int[]{-k + (m - n), k};
		return new int[]{k, k - (m - n - t)};
	}

	/** World position of a plot-local point of a building (plots are turned so their front faces the town centre). */
	public int[] worldPoint(Building b, int lx, int lz) {
		int dx = x - (b.px + PLOT / 2), dz = z - (b.pz + PLOT / 2);
		int rot = Math.abs(dx) < 3 && Math.abs(dz) < 3 ? 0 : Math.abs(dz) >= Math.abs(dx) ? (dz > 0 ? 2 : 0) : (dx > 0 ? 1 : 3);
		int m = PLOT - 1;
		int[] t = switch (rot) {
			case 1 -> new int[]{m - lz, lx};
			case 2 -> new int[]{m - lx, m - lz};
			case 3 -> new int[]{lz, m - lx};
			default -> new int[]{lx, lz};
		};
		return new int[]{b.px + t[0], b.pz + t[1]};
	}

	/** The barracks yard: plot-local standing places for a rank of soldiers (three ranks of four). */
	public static final int[][] MUSTER = {{2, 7}, {4, 7}, {6, 7}, {8, 7}, {3, 8}, {5, 8}, {7, 8}, {9, 8}, {2, 9}, {4, 9}, {6, 9}, {8, 9}};

	public int count(BuildingType type) {
		int c = 0;
		for (Building b : buildings) if (b.type == type) c++;
		return c;
	}

	public boolean busy() {
		for (Building b : buildings) if (!b.done()) return true;
		return false;
	}

	public CompoundTag save() {
		CompoundTag t = new CompoundTag();
		t.putInt("id", id);
		t.putInt("k", kingdom);
		t.putIntArray("name", name);
		t.putInt("x", x);
		t.putInt("z", z);
		t.putBoolean("cap", capital);
		t.putInt("next", nextPlot);
		t.putFloat("gar", garrison);
		t.putInt("wall", wall);
		t.putInt("wallr", wallR);
		t.putFloat("wallp", wallProgress);
		t.putLong("walld", wallDoneDay);
		t.putLongArray("wallb", wallBits);
		ListTag list = new ListTag();
		for (Building b : buildings) list.add(b.save());
		t.put("b", list);
		return t;
	}

	public static Settlement load(CompoundTag t) {
		Settlement s = new Settlement(t.getIntOr("id", 0), t.getIntOr("k", 0), t.getIntArray("name").orElse(new int[]{0, 40}),
				t.getIntOr("x", 0), t.getIntOr("z", 0), t.getBooleanOr("cap", false));
		s.nextPlot = t.getIntOr("next", 1);
		s.garrison = t.getFloatOr("gar", 0);
		s.wall = t.getIntOr("wall", 0);
		s.wallR = t.getIntOr("wallr", s.wall > 0 ? 52 : Settlement.radiusFor(s.capital));
		s.wallProgress = t.getFloatOr("wallp", 0);
		s.wallDoneDay = t.getLongOr("walld", 0);
		long[] wb = t.getLongArray("wallb").orElse(new long[10]);
		System.arraycopy(wb, 0, s.wallBits, 0, Math.min(wb.length, 10));
		for (Tag b : t.getListOrEmpty("b")) if (b instanceof CompoundTag c) s.buildings.add(Building.load(c));
		return s;
	}

	/** One building on one plot. */
	public static final class Building {
		public final BuildingType type;
		public final int px, pz;
		/** Style (era) it was started in: a town keeps a history of its ages. */
		public int style;
		/** Simulation day it was completed (a fresh building is still being finished by builders; older ones simply stand). */
		public long doneDay;
		public float progress;
		/** Base height once placed in the world (Integer.MIN_VALUE: not yet touched in the world). */
		public int y = Integer.MIN_VALUE;
		/** How many blocks of its blueprint are already in the world. */
		public int placed;
		/** Dye of the flags currently in the world (-1 none). */
		public int flagDye = -1;
		public boolean ruined;

		public Building(BuildingType type, int px, int pz, int style) {
			this.type = type;
			this.px = px;
			this.pz = pz;
			this.style = style;
		}

		public boolean done() {
			return progress >= 1f;
		}

		public CompoundTag save() {
			CompoundTag t = new CompoundTag();
			t.putInt("t", type.ordinal());
			t.putInt("px", px);
			t.putInt("pz", pz);
			t.putInt("st", style);
			t.putFloat("p", progress);
			t.putInt("y", y);
			t.putInt("pl", placed);
			t.putInt("fd", flagDye);
			t.putBoolean("r", ruined);
			t.putLong("dd", doneDay);
			return t;
		}

		public static Building load(CompoundTag t) {
			Building b = new Building(BuildingType.of(t.getIntOr("t", 0)), t.getIntOr("px", 0), t.getIntOr("pz", 0), t.getIntOr("st", 0));
			b.progress = t.getFloatOr("p", 0);
			b.y = t.getIntOr("y", Integer.MIN_VALUE);
			b.placed = t.getIntOr("pl", 0);
			b.flagDye = t.getIntOr("fd", -1);
			b.ruined = t.getBooleanOr("r", false);
			b.doneDay = t.getLongOr("dd", 0);
			return b;
		}
	}
}
