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
	public long[] wallBits = new long[7];
	/** Half-size of the wall square, in blocks: it encloses the farthest plot of a town. */
	public static final int WALL_R = 52;
	public static final int WALL_LEN = WALL_R * 8;

	/** Block position of wall column i (0..WALL_LEN-1), walking the square clockwise from the north-west corner. */
	public int[] wallPos(int i) {
		int side = i / (WALL_R * 2), pos = i % (WALL_R * 2);
		return switch (side) {
			case 0 -> new int[]{x - WALL_R + pos, z - WALL_R};
			case 1 -> new int[]{x + WALL_R, z - WALL_R + pos};
			case 2 -> new int[]{x + WALL_R - pos, z + WALL_R};
			default -> new int[]{x - WALL_R, z + WALL_R - pos};
		};
	}

	public boolean wallBit(int i) {
		return (wallBits[i >> 6] >>> (i & 63) & 1L) != 0;
	}

	public void setWallBit(int i) {
		wallBits[i >> 6] |= 1L << (i & 63);
	}

	/** Gate gaps: seven columns around the middle of each side. */
	public static boolean gate(int i) {
		int pos = i % (WALL_R * 2);
		return Math.abs(pos - WALL_R) <= 3;
	}

	public Settlement(int id, int kingdom, int[] name, int x, int z, boolean capital) {
		this.id = id;
		this.kingdom = kingdom;
		this.name = name;
		this.x = x;
		this.z = z;
		this.capital = capital;
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
		s.wallProgress = t.getFloatOr("wallp", 0);
		s.wallDoneDay = t.getLongOr("walld", 0);
		long[] wb = t.getLongArray("wallb").orElse(new long[7]);
		System.arraycopy(wb, 0, s.wallBits, 0, Math.min(wb.length, 7));
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
