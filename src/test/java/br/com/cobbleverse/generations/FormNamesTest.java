package br.com.cobbleverse.generations;
import br.com.cobbleverse.generations.rules.FormNames;
import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;
class FormNamesTest {
 @Test void preservesOriginalMegaRulesForRepresentativeTokens() {
  for(String root:new String[]{"mega", "primal", "Mega Evolution", "none", "galarian", "x"})
   for(String suffix:new String[]{"", "=none", "_false", "-normal", "=base", "=true", "-x", "none", "=NONE", "\nnone"}) {
    String value=root+suffix; String key=value.toLowerCase(Locale.ROOT);
    boolean expected=!key.matches(".*(?:=|_|-)(?:none|false|normal|base)$") && (key.contains("mega") || key.contains("primal"));
    assertEquals(expected,FormNames.activeMega(value),value);
   }
 }
 @Test void normalizesFormsAndAliases() {
  assertEquals("normal",FormNames.canonical(null)); assertEquals("alola",FormNames.canonical(" Alolan "));
  assertEquals("galar",FormNames.canonical("GALARIAN")); assertEquals("hisui",FormNames.canonical("Hisuian"));
  assertEquals("paldea",FormNames.canonical("paldean")); assertEquals("mega_x",FormNames.canonical(" Mega - X "));
 }

 @Test void normalizesSeparatorsWithTheSameAsciiRules() {
  for (String separator : new String[]{"-", " ", "\t", "\n", "\u000B", "\f", "\r", " -\t "}) {
   assertEquals("mega_x", FormNames.canonical(" Mega" + separator + "X "));
  }
  assertEquals("mega\u00A0x", FormNames.canonical("Mega\u00A0X"));
  assertEquals("normal", FormNames.canonical("Normal"));
 }
}
