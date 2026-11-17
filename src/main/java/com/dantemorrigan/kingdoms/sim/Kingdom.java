package com.dantemorrigan.kingdoms.sim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One kingdom: its ruler, people, stores, army, knowledge, temper and its relations with the others. */
public final class Kingdom {
	/** Map colors per dye id (vanilla DyeColor order), tuned to read well over terrain. */
	public static final int[] DYE_RGB = {
			0xF0F0F0, 0xF08A24, 0xC94FD0, 0x5FB4F0, 0xF2D33A, 0x8BD43A, 0xF08BB0, 0x6B6B6B,
			0xA0A0A0, 0x23B0B0, 0x8040D0, 0x3456D8, 0x8A5A30, 0x3E9A3A, 0xD83A34, 0x262626};
	/** Order in which new kingdoms take colors. */
	public static final int[] DYE_ORDER = {14, 11, 4, 13, 10, 1, 9, 2, 5, 3, 6, 12, 0, 15, 7, 8};

	public final int id;
	public int[] name;
	public int dye;
	public boolean alive = true;
	public boolean rebel;
	public int era;
	public float knowledge;

	public float population = 20, food = 60, wood = 60, stone = 30, iron = 0, gold = 20;
	public float soldiers = 4;
	public float stability = 70;
	/** Personality, 0..1. */
	public float aggression, expansion, diplomacy, piety;

	public int[] king;
	public int kingNumber = 1;
	public float kingAge = 30;

	public int capital;
	public int cells;
	public long founded;

	public final Map<Integer, Float> relations = new HashMap<>();
	public final Set<Integer> wars = new HashSet<>();
	public final Set<Integer> allies = new HashSet<>();
	public final Map<Integer, Float> weariness = new HashMap<>();
	/** Simulation day of the last war or peace with each kingdom (to avoid flip-flopping). */
	public final Map<Integer, Long> lastTreaty = new HashMap<>();

	public Kingdom(int id) {
		this.id = id;
	}

	public Era era() {
		return Era.of(era);
	}

	public int color() {
		return DYE_RGB[Math.floorMod(dye, 16)];
	}

	public float might() {
		return soldiers * era().might;
	}

	public float relation(int other) {
		return relations.getOrDefault(other, 0f);
	}

	public void addRelation(int other, float delta) {
		relations.put(other, Math.max(-100, Math.min(100, relation(other) + delta)));
	}

	public MutableComponent title() {
		return Component.translatable(rebel ? "kingdoms.fmt.rebels" : "kingdoms.fmt.kingdom", Names.component(name));
	}

	public MutableComponent kingTitle() {
		return Component.translatable("kingdoms.fmt.king", Names.component(king), Names.roman(kingNumber));
	}

	public CompoundTag save() {
		CompoundTag t = new CompoundTag();
		t.putInt("id", id);
		t.putIntArray("name", name);
		t.putInt("dye", dye);
		t.putBoolean("alive", alive);
		t.putBoolean("rebel", rebel);
		t.putInt("era", era);
		t.putFloat("know", knowledge);
		t.putFloat("pop", population);
		t.putFloat("food", food);
		t.putFloat("wood", wood);
		t.putFloat("stone", stone);
		t.putFloat("iron", iron);
		t.putFloat("gold", gold);
		t.putFloat("sold", soldiers);
		t.putFloat("stab", stability);
		t.putFloat("agg", aggression);
		t.putFloat("exp", expansion);
		t.putFloat("dip", diplomacy);
		t.putFloat("pie", piety);
		t.putIntArray("king", king);
		t.putInt("kn", kingNumber);
		t.putFloat("ka", kingAge);
		t.putInt("cap", capital);
		t.putLong("founded", founded);
		t.put("rel", floatMap(relations));
		t.put("wear", floatMap(weariness));
		t.putIntArray("wars", wars.stream().mapToInt(Integer::intValue).toArray());
		t.putIntArray("allies", allies.stream().mapToInt(Integer::intValue).toArray());
		ListTag treaties = new ListTag();
		lastTreaty.forEach((k, v) -> {
			CompoundTag e = new CompoundTag();
			e.putInt("k", k);
			e.putLong("d", v);
			treaties.add(e);
		});
		t.put("treaty", treaties);
		return t;
	}

	public static Kingdom load(CompoundTag t) {
		Kingdom k = new Kingdom(t.getIntOr("id", 0));
		k.name = t.getIntArray("name").orElse(new int[]{0, 40});
		k.dye = t.getIntOr("dye", 14);
		k.alive = t.getBooleanOr("alive", true);
		k.rebel = t.getBooleanOr("rebel", false);
		k.era = t.getIntOr("era", 0);
		k.knowledge = t.getFloatOr("know", 0);
		k.population = t.getFloatOr("pop", 20);
		k.food = t.getFloatOr("food", 0);
		k.wood = t.getFloatOr("wood", 0);
		k.stone = t.getFloatOr("stone", 0);
		k.iron = t.getFloatOr("iron", 0);
		k.gold = t.getFloatOr("gold", 0);
		k.soldiers = t.getFloatOr("sold", 0);
		k.stability = t.getFloatOr("stab", 50);
		k.aggression = t.getFloatOr("agg", 0.5f);
		k.expansion = t.getFloatOr("exp", 0.5f);
		k.diplomacy = t.getFloatOr("dip", 0.5f);
		k.piety = t.getFloatOr("pie", 0.5f);
		k.king = t.getIntArray("king").orElse(new int[]{1, 2});
		k.kingNumber = t.getIntOr("kn", 1);
		k.kingAge = t.getFloatOr("ka", 30);
		k.capital = t.getIntOr("cap", 0);
		k.founded = t.getLongOr("founded", 0);
		readFloatMap(t.getCompoundOrEmpty("rel"), k.relations);
		readFloatMap(t.getCompoundOrEmpty("wear"), k.weariness);
		for (int w : t.getIntArray("wars").orElse(new int[0])) k.wars.add(w);
		for (int a : t.getIntArray("allies").orElse(new int[0])) k.allies.add(a);
		for (Tag e : t.getListOrEmpty("treaty")) if (e instanceof CompoundTag c) k.lastTreaty.put(c.getIntOr("k", 0), c.getLongOr("d", 0));
		return k;
	}

	private static CompoundTag floatMap(Map<Integer, Float> map) {
		CompoundTag t = new CompoundTag();
		map.forEach((k, v) -> t.putFloat(String.valueOf(k), v));
		return t;
	}

	private static void readFloatMap(CompoundTag t, Map<Integer, Float> into) {
		for (String key : t.keySet()) {
			try {
				into.put(Integer.parseInt(key), t.getFloatOr(key, 0));
			} catch (NumberFormatException ignored) {}
		}
	}
}
