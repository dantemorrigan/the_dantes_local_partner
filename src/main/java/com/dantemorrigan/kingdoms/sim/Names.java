package com.dantemorrigan.kingdoms.sim;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;

/**
 * Bilingual generated names. A name is a list of syllable ids; every syllable has a translation key
 * ({@code kingdoms.syl.N} lowercase, {@code kingdoms.sylc.N} capitalized) in both lang files, so the same name reads
 * "Arvelheim" in English and "Арвелхейм" in Russian, on the map, in chat and over the heads of citizens.
 */
public final class Names {
	/** en/ru pairs. 0..STARTS-1 may begin a name, the rest are endings. Keep in sync with the lang files. */
	public static final String[][] SYLLABLES = {
			{"ar", "ар"}, {"bel", "бел"}, {"cor", "кор"}, {"dor", "дор"}, {"el", "эл"}, {"fen", "фен"}, {"gar", "гар"},
			{"hal", "хал"}, {"is", "ис"}, {"kar", "кар"}, {"lor", "лор"}, {"mar", "мар"}, {"nor", "нор"}, {"or", "ор"},
			{"pel", "пел"}, {"quen", "квен"}, {"ran", "ран"}, {"sil", "сил"}, {"tor", "тор"}, {"ul", "ул"}, {"val", "вал"},
			{"wen", "вен"}, {"zar", "зар"}, {"mir", "мир"}, {"dan", "дан"}, {"vel", "вел"}, {"thal", "тал"}, {"ros", "рос"},
			{"gor", "гор"}, {"lin", "лин"}, {"mor", "мор"}, {"ka", "ка"}, {"ri", "ри"}, {"bran", "бран"}, {"ed", "эд"},
			{"al", "ал"}, {"ber", "бер"}, {"os", "ос"}, {"ste", "сте"}, {"vi", "ви"},
			// endings
			{"ia", "ия"}, {"or", "ор"}, {"heim", "хейм"}, {"grad", "град"}, {"mar", "мар"}, {"on", "он"}, {"ell", "элл"},
			{"os", "ос"}, {"ara", "ара"}, {"wyn", "вин"}, {"ard", "ард"}, {"ic", "ик"}, {"ven", "вен"}, {"us", "ус"},
			{"burg", "бург"}, {"stad", "стад"}, {"ford", "форд"}, {"mund", "мунд"}, {"ric", "рик"}, {"an", "ан"},
	};
	public static final int STARTS = 40;

	public static int[] place(RandomSource r) {
		return r.nextInt(3) == 0
				? new int[]{r.nextInt(STARTS), r.nextInt(STARTS), STARTS + r.nextInt(SYLLABLES.length - STARTS)}
				: new int[]{r.nextInt(STARTS), STARTS + r.nextInt(SYLLABLES.length - STARTS)};
	}

	public static int[] person(RandomSource r) {
		int[] personEnds = {45, 50, 51, 53, 57, 58, 59, 41, 46};
		return r.nextBoolean()
				? new int[]{r.nextInt(STARTS), personEnds[r.nextInt(personEnds.length)]}
				: new int[]{r.nextInt(STARTS), r.nextInt(STARTS)};
	}

	public static MutableComponent component(int[] syl) {
		MutableComponent c = Component.empty();
		for (int i = 0; i < syl.length; i++) {
			int s = Math.floorMod(syl[i], SYLLABLES.length);
			c.append(Component.translatable((i == 0 ? "kingdoms.sylc." : "kingdoms.syl.") + s));
		}
		return c;
	}

	public static String roman(int n) {
		String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV"};
		return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
	}

	private Names() {}
}
