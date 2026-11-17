package com.dantemorrigan.kingdoms.sim;

import java.util.Locale;

/**
 * What a kingdom can build. Costs are paid when construction starts; output is produced every simulation day once
 * the building is complete. Every type also has a real structure in the world ({@code world.Structures}).
 */
public enum BuildingType {
	//            era            wood stone iron gold  days  food  wood stone iron gold know  housing
	KEEP(Era.TRIBAL,              0,    0,   0,   0,  10,  0.6f, 0.3f, 0.2f, 0,    0.5f, 0.4f, 12),
	TOWN_HALL(Era.TRIBAL,        30,   20,   0,  10,  14,  0.2f, 0.1f, 0,    0,    0.3f, 0.2f, 6),
	HOUSE(Era.TRIBAL,            14,    4,   0,   0,   6,  0,    0,    0,    0,    0.05f, 0.02f, 8),
	FARM(Era.TRIBAL,              8,    0,   0,   0,   5,  1.6f, 0,    0,    0,    0,    0,    0),
	LUMBER_CAMP(Era.TRIBAL,       6,    2,   0,   0,   5,  0,    1.4f, 0,    0,    0,    0,    0),
	QUARRY(Era.TRIBAL,           10,    0,   0,   0,   6,  0,    0,    1.2f, 0,    0,    0,    0),
	MINE(Era.FEUDAL,             16,   12,   0,   0,   9,  0,    0,    0.4f, 0.6f, 0.15f, 0,   0),
	BARRACKS(Era.FEUDAL,         24,   20,   4,   6,  10,  0,    0,    0,    0,    0,    0,    0),
	SMITHY(Era.FEUDAL,           14,   18,   6,   4,   9,  0,    0,    0,    0,    0,    0.1f, 0),
	MARKET(Era.FEUDAL,           20,    8,   0,   6,   8,  0,    0,    0,    0,    1.0f, 0.1f, 0),
	TEMPLE(Era.FEUDAL,           18,   26,   0,  14,  14,  0,    0,    0,    0,    0,    0.3f, 0),
	WALL_TOWER(Era.CASTLE,        6,   30,   2,   4,   9,  0,    0,    0,    0,    0,    0,    0),
	LIBRARY(Era.CASTLE,          26,   20,   0,  16,  12,  0,    0,    0,    0,    0,    1.2f, 0),
	WORKSHOP(Era.RENAISSANCE,    24,   30,  10,  12,  12,  0,    0.5f, 0.5f, 0.4f, 0.4f, 0.4f, 0),
	FACTORY(Era.INDUSTRIAL,      20,   60,  30,  30,  18,  0,    0,    0,    1.2f, 1.2f, 0.6f, 0);

	public final Era era;
	public final int wood, stone, iron, gold, days;
	public final float food, outWood, outStone, outIron, outGold, knowledge;
	public final int housing;

	BuildingType(Era era, int wood, int stone, int iron, int gold, int days, float food, float outWood, float outStone,
				 float outIron, float outGold, float knowledge, int housing) {
		this.era = era;
		this.wood = wood;
		this.stone = stone;
		this.iron = iron;
		this.gold = gold;
		this.days = days;
		this.food = food;
		this.outWood = outWood;
		this.outStone = outStone;
		this.outIron = outIron;
		this.outGold = outGold;
		this.knowledge = knowledge;
		this.housing = housing;
	}

	public String key() {
		return "kingdoms.building." + name().toLowerCase(Locale.ROOT);
	}

	public static BuildingType of(int ordinal) {
		BuildingType[] v = values();
		return v[Math.max(0, Math.min(v.length - 1, ordinal))];
	}
}
