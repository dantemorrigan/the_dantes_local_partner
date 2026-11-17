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
	/** 0 attack, 1 border patrol, 2 defence (intercepts an enemy army near own towns). */
	public int mission;
	/** Set by the world each tick while the army has living soldiers in the world: real fighting replaces the arithmetic. */
	public transient boolean observed;
	/** Raised at a barracks: the block where the ranks form, and whether they stand a while before marching out. */
	public transient boolean hasStart, mustering;
	public transient int startX, startZ;
	/** Route over land (cell indices) and the next waypoint on it. */
	public transient int[] path;
	public transient int pi;
	/** Ordered home: it merges back into the kingdom on arrival. */
	public transient boolean home;
	public transient long thought, lastLog;
	/** 0..1: share of the soldiers in the world that stand at the objective (a siege only advances with soldiers there). */
	public transient float pressure = 1f;
	/** Soldiers killed in the world since the last simulated day (for the map). */
	public transient float dead;

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
		t.putInt("m", mission);
		return t;
	}

	public static Army load(CompoundTag t) {
		Army a = new Army(t.getIntOr("id", 0), t.getIntOr("k", 0), t.getFloatOr("x", 0), t.getFloatOr("z", 0), t.getFloatOr("s", 0));
		a.tx = t.getFloatOr("tx", a.x);
		a.tz = t.getFloatOr("tz", a.z);
		a.target = t.getIntOr("tg", 0);
		a.fighting = t.getIntOr("f", 0);
		a.mission = t.getIntOr("m", 0);
		return a;
	}
}
