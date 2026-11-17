package com.dantemorrigan.kingdoms.sim;

/** Ages a kingdom passes through. Each one changes how it builds, how strong its soldiers are and what it may build. */
public enum Era {
	TRIBAL(0, 1.0f), FEUDAL(150, 1.6f), CASTLE(600, 2.4f), RENAISSANCE(1500, 3.4f), INDUSTRIAL(3400, 5.0f);

	/** Knowledge needed to enter this era. */
	public final int knowledge;
	/** Combat value of one soldier. */
	public final float might;

	Era(int knowledge, float might) {
		this.knowledge = knowledge;
		this.might = might;
	}

	public static Era of(int ordinal) {
		Era[] v = values();
		return v[Math.max(0, Math.min(v.length - 1, ordinal))];
	}

	public Era next() {
		return this == INDUSTRIAL ? this : values()[ordinal() + 1];
	}

	public String key() {
		return "kingdoms.era." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
