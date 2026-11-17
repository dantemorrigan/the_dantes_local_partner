package com.dantemorrigan.kingdoms.sim;

import net.minecraft.nbt.CompoundTag;

/** A field army on the strategic map. Position in cell units (floating), strength in soldiers. */
public final class Army {
	public final int id;
	public int kingdom;
	public float x, z;
	public float tx, tz;
	public float soldiers;
	/** Kingdom it marches against (0: going home / idle). */
	public int target;
	/** Ticks spent fighting this turn (shown as a battle on the map). */
	public int fighting;

	public Army(int id, int kingdom, float x, float z, float soldiers) {
		this.id = id;
		this.kingdom = kingdom;
		this.x = this.tx = x;
		this.z = this.tz = z;
		this.soldiers = soldiers;
	}

	public CompoundTag save() {
		CompoundTag t = new CompoundTag();
		t.putInt("id", id);
		t.putInt("k", kingdom);
		t.putFloat("x", x);
		t.putFloat("z", z);
		t.putFloat("tx", tx);
		t.putFloat("tz", tz);
		t.putFloat("s", soldiers);
		t.putInt("tg", target);
		t.putInt("f", fighting);
		return t;
	}

	public static Army load(CompoundTag t) {
		Army a = new Army(t.getIntOr("id", 0), t.getIntOr("k", 0), t.getFloatOr("x", 0), t.getFloatOr("z", 0), t.getFloatOr("s", 0));
		a.tx = t.getFloatOr("tx", a.x);
		a.tz = t.getFloatOr("tz", a.z);
		a.target = t.getIntOr("tg", 0);
		a.fighting = t.getIntOr("f", 0);
		return a;
	}
}
