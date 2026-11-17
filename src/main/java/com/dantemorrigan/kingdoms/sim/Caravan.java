package com.dantemorrigan.kingdoms.sim;

import net.minecraft.nbt.CompoundTag;

/**
 * A trade caravan walking a real land route between two towns. It carries goods out, barters them at the far market
 * and brings the exchange home; it can be turned back or plundered when a war breaks out along the way.
 */
public final class Caravan {
	public static final String[] GOODS = {"food", "wood", "stone", "iron", "gold"};
	/** Gold value of one unit of each good. */
	public static final float[] PRICE = {0.5f, 0.4f, 0.5f, 2.0f, 1.0f};

	public final int id;
	public int kingdom, from, to;
	/** Cells (index z*N+x) from the origin town to the destination. */
	public int[] path = new int[0];
	/** Position along the path, in cells. */
	public float at;
	/** false: outbound, true: coming home. */
	public boolean returning;
	public int giveType, giveAmt, getType, getAmt;
	public float profit;
	/** The world has a living merchant for it: it walks at a pace a person can follow. */
	public transient boolean observed;

	public Caravan(int id, int kingdom, int from, int to) {
		this.id = id;
		this.kingdom = kingdom;
		this.from = from;
		this.to = to;
	}

	public float x(int n) {
		return cell(n, true);
	}

	public float z(int n) {
		return cell(n, false);
	}

	private float cell(int n, boolean x) {
		if (path.length == 0) return 0;
		float p = Math.max(0, Math.min(path.length - 1, at));
		int i = (int) p;
		int j = Math.min(path.length - 1, i + 1);
		float f = p - i;
		float a = x ? path[i] % n : path[i] / n, b = x ? path[j] % n : path[j] / n;
		return a + (b - a) * f + 0.5f;
	}

	public CompoundTag save() {
		CompoundTag t = new CompoundTag();
		t.putInt("id", id);
		t.putInt("k", kingdom);
		t.putInt("from", from);
		t.putInt("to", to);
		t.putIntArray("path", path);
		t.putFloat("at", at);
		t.putBoolean("ret", returning);
		t.putInt("gt", giveType);
		t.putInt("ga", giveAmt);
		t.putInt("rt", getType);
		t.putInt("ra", getAmt);
		t.putFloat("pr", profit);
		return t;
	}

	public static Caravan load(CompoundTag t) {
		Caravan c = new Caravan(t.getIntOr("id", 0), t.getIntOr("k", 0), t.getIntOr("from", 0), t.getIntOr("to", 0));
		c.path = t.getIntArray("path").orElse(new int[0]);
		c.at = t.getFloatOr("at", 0);
		c.returning = t.getBooleanOr("ret", false);
		c.giveType = t.getIntOr("gt", 0);
		c.giveAmt = t.getIntOr("ga", 0);
		c.getType = t.getIntOr("rt", 0);
		c.getAmt = t.getIntOr("ra", 0);
		c.profit = t.getFloatOr("pr", 0);
		return c;
	}
}
