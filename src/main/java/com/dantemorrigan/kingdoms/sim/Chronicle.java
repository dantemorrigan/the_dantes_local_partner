package com.dantemorrigan.kingdoms.sim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * The history of the realm. Entries keep their arguments self-contained (names as syllables), so they read correctly
 * in either language and long after the kingdoms they mention are gone.
 */
public final class Chronicle {
	/** Importance: 0 minor (map only), 1 notable (map + toast), 2 major (also chat). */
	public record Entry(long day, String key, List<String> args, int level, int x, int z) {
		public CompoundTag save() {
			CompoundTag t = new CompoundTag();
			t.putLong("d", day);
			t.putString("k", key);
			ListTag a = new ListTag();
			for (String s : args) a.add(StringTag.valueOf(s));
			t.put("a", a);
			t.putInt("l", level);
			t.putInt("x", x);
			t.putInt("z", z);
			return t;
		}

		public static Entry load(CompoundTag t) {
			List<String> args = new ArrayList<>();
			for (Tag a : t.getListOrEmpty("a")) args.add(a.asString().orElse(""));
			return new Entry(t.getLongOr("d", 0), t.getStringOr("k", ""), args, t.getIntOr("l", 0), t.getIntOr("x", 0), t.getIntOr("z", 0));
		}

		public MutableComponent text() {
			Object[] parts = new Object[args.size()];
			for (int i = 0; i < parts.length; i++) parts[i] = arg(args.get(i));
			return Component.translatable(key, parts);
		}
	}

	public static String kingdom(Kingdom k) {
		return "K" + (k.rebel ? 1 : 0) + ":" + join(k.name);
	}

	public static String name(int[] syl) {
		return "N:" + join(syl);
	}

	public static String king(Kingdom k) {
		return "R" + k.kingNumber + ":" + join(k.king);
	}

	public static String tr(String key) {
		return "T:" + key;
	}

	private static String join(int[] syl) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < syl.length; i++) {
			if (i > 0) b.append(',');
			b.append(syl[i]);
		}
		return b.toString();
	}

	private static int[] split(String s) {
		String[] p = s.split(",");
		int[] r = new int[p.length];
		for (int i = 0; i < p.length; i++) {
			try {
				r[i] = Integer.parseInt(p[i].trim());
			} catch (NumberFormatException e) {
				r[i] = 0;
			}
		}
		return r;
	}

	public static Component arg(String a) {
		if (a.startsWith("K0:") || a.startsWith("K1:")) {
			return Component.translatable(a.charAt(1) == '1' ? "kingdoms.fmt.rebels" : "kingdoms.fmt.kingdom", Names.component(split(a.substring(3))));
		}
		if (a.startsWith("N:")) return Names.component(split(a.substring(2)));
		if (a.startsWith("R")) {
			int colon = a.indexOf(':');
			if (colon > 1) {
				int n;
				try {
					n = Integer.parseInt(a.substring(1, colon));
				} catch (NumberFormatException e) {
					n = 1;
				}
				return Component.translatable("kingdoms.fmt.king", Names.component(split(a.substring(colon + 1))), Names.roman(n));
			}
		}
		if (a.startsWith("T:")) return Component.translatable(a.substring(2));
		return Component.literal(a);
	}

	private Chronicle() {}
}
