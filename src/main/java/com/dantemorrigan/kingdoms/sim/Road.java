package com.dantemorrigan.kingdoms.sim;

import net.minecraft.nbt.CompoundTag;

/**
 * A road between two towns over land, with bridges and crossings where it meets water. Its level is the age it was
 * built or last upgraded in: 0 track, 1 horse road, 2 stone road, 3 paved road, 4 railway.
 */
public final class Road {
	public final int id;
	public int kingdom, a, b, level;
	/** Cells from town a to town b (cell indices, 4-connected). */
	public int[] cells = new int[0];
	public float progress;
	public long doneDay;
	/** Which road blocks are already in the world (one bit per block index). */
	public long[] bits = new long[0];

	public Road(int id, int kingdom, int a, int b, int level) {
		this.id = id;
		this.kingdom = kingdom;
		this.a = a;
		this.b = b;
		this.level = level;
	}

	/** Blocks along the road: 16 per cell step. */
	public int blocks() {
		return Math.max(1, (cells.length - 1) * 16 + 1);
	}

	public boolean bit(int i) {
		return i >> 6 < bits.length && (bits[i >> 6] >>> (i & 63) & 1L) != 0;
	}

	public void setBit(int i) {
		if (bits.length <= i >> 6) bits = java.util.Arrays.copyOf(bits, (i >> 6) + 8);
		bits[i >> 6] |= 1L << (i & 63);
	}

	public CompoundTag save() {
		CompoundTag t = new CompoundTag();
		t.putInt("id", id);
		t.putInt("k", kingdom);
		t.putInt("a", a);
		t.putInt("b", b);
		t.putInt("lv", level);
		t.putIntArray("cells", cells);
		t.putFloat("p", progress);
		t.putLong("dd", doneDay);
		t.putLongArray("bits", bits);
		return t;
	}

	public static Road load(CompoundTag t) {
		Road r = new Road(t.getIntOr("id", 0), t.getIntOr("k", 0), t.getIntOr("a", 0), t.getIntOr("b", 0), t.getIntOr("lv", 0));
		r.cells = t.getIntArray("cells").orElse(new int[0]);
		r.progress = t.getFloatOr("p", 0);
		r.doneDay = t.getLongOr("dd", 0);
		r.bits = t.getLongArray("bits").orElse(new long[0]);
		return r;
	}
}
