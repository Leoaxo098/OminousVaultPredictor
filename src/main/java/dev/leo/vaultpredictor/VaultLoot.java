package dev.leo.vaultpredictor;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class VaultLoot {
    enum Kind { PLAIN, STACK, BOTTLE, GEAR, BOOK, FIXED_BOOK }

    record Enchantment(String name, int maxLevel) {
        @Override public String toString() { return readableName(name); }
    }

    record EnchantedLevel(Enchantment enchantment, int level) {
        String token() { return enchantment.name() + " " + level; }
        String label() { return enchantment + " " + roman(level); }
    }

    enum Item {
        EMERALD("emerald", "Emerald", Kind.STACK),
        WIND_CHARGE("wind_charge", "Wind charge", Kind.STACK),
        SLOWNESS_ARROW("tipped_arrow_slowness", "Tipped arrow (slowness)", Kind.STACK),
        DIAMOND("diamond", "Diamond", Kind.STACK),
        OMINOUS_BOTTLE("ominous_bottle", "Ominous bottle", Kind.BOTTLE),
        EMERALD_BLOCK("emerald_block", "Block of emerald", Kind.PLAIN),
        IRON_BLOCK("iron_block", "Block of iron", Kind.PLAIN),
        GOLDEN_APPLE("golden_apple", "Golden apple", Kind.PLAIN),
        DIAMOND_BLOCK("diamond_block", "Block of diamond", Kind.PLAIN),
        CROSSBOW("crossbow", "Crossbow (enchanted)", "Crossbow", Kind.GEAR,
                new Enchantment("mending", 1), new Enchantment("multishot", 1), new Enchantment("piercing", 4),
                new Enchantment("quick_charge", 3), new Enchantment("unbreaking", 3), new Enchantment("vanishing_curse", 1)),
        DIAMOND_AXE("diamond_axe", "Diamond axe (enchanted)", "Diamond axe", Kind.GEAR,
                new Enchantment("efficiency", 5), new Enchantment("fortune", 3), new Enchantment("mending", 1),
                new Enchantment("silk_touch", 1), new Enchantment("unbreaking", 3), new Enchantment("vanishing_curse", 1)),
        DIAMOND_CHESTPLATE("diamond_chestplate", "Diamond chestplate (enchanted)", "Diamond chestplate", Kind.GEAR,
                new Enchantment("binding_curse", 1), new Enchantment("blast_protection", 4), new Enchantment("fire_protection", 4),
                new Enchantment("mending", 1), new Enchantment("projectile_protection", 4), new Enchantment("protection", 4),
                new Enchantment("thorns", 3), new Enchantment("unbreaking", 3), new Enchantment("vanishing_curse", 1)),
        COMBAT_BOOK("book", "Book (knockback/punch/smite/looting/multishot)", "Enchanted book · Combat", Kind.BOOK,
                new Enchantment("knockback", 2), new Enchantment("punch", 2), new Enchantment("smite", 5),
                new Enchantment("looting", 3), new Enchantment("multishot", 1)),
        MACE_BOOK("book", "Book (breach/density)", "Enchanted book · Mace", Kind.BOOK,
                new Enchantment("breach", 4), new Enchantment("density", 5)),
        WIND_BURST_BOOK("book", "Book (wind burst)", "Enchanted book · Wind Burst", Kind.FIXED_BOOK),
        ENCHANTED_GOLDEN_APPLE("enchanted_golden_apple", "Enchanted golden apple", Kind.PLAIN),
        FLOW_ARMOR_TRIM("flow_armor_trim_smithing_template", "Flow armor trim", Kind.PLAIN),
        FLOW_BANNER_PATTERN("flow_banner_pattern", "Flow banner pattern", Kind.PLAIN),
        MUSIC_DISC_CREATOR("music_disc_creator", "Music disc creator", Kind.PLAIN),
        HEAVY_CORE("heavy_core", "Heavy core", Kind.PLAIN);

        final String token;
        final String profileLabel;
        final String label;
        final Kind kind;
        final List<Enchantment> enchantments;

        Item(String token, String label, Kind kind, Enchantment... enchantments) {
            this(token, label, label, kind, enchantments);
        }

        Item(String token, String profileLabel, String label, Kind kind, Enchantment... enchantments) {
            this.token = token;
            this.profileLabel = profileLabel;
            this.label = label;
            this.kind = kind;
            this.enchantments = List.of(enchantments);
        }

        boolean matches(String search) {
            String names = profileLabel + " " + label + " " + token.replace('_', ' ') + " " + enchantments;
            String haystack = names.toLowerCase(Locale.ROOT);
            return searchTerms(search).stream().allMatch(haystack::contains);
        }

        Optional<Enchantment> searchedEnchantment(String search) {
            String itemLabel = label.toLowerCase(Locale.ROOT);
            List<String> terms = searchTerms(search).stream().filter(term -> !itemLabel.contains(term)).toList();
            if (terms.isEmpty()) return Optional.empty();

            List<Enchantment> choices = enchantments.stream().filter(enchantment -> {
                String name = enchantment.toString().toLowerCase(Locale.ROOT);
                return terms.stream().allMatch(name::contains);
            }).toList();
            return choices.size() == 1 ? Optional.of(choices.getFirst()) : Optional.empty();
        }
    }

    record Entry(Item item, int quantity, String bottleLevel, List<EnchantedLevel> enchantments) {
        Entry {
            enchantments = List.copyOf(enchantments);
        }

        String token() {
            return switch (item.kind) {
                case PLAIN -> item.token;
                case STACK -> item.token + " x" + quantity;
                case BOTTLE -> item.token + " " + bottleLevel + (quantity > 1 ? " x" + quantity : "");
                case FIXED_BOOK -> "book[wind_burst]";
                case GEAR, BOOK -> item.token + "[" + enchantments.stream()
                        .map(EnchantedLevel::token).collect(Collectors.joining(",")) + "]";
            };
        }

        String label() {
            return switch (item.kind) {
                case PLAIN, FIXED_BOOK -> item.label;
                case STACK -> item.label + " ×" + quantity;
                case BOTTLE -> item.label + " " + bottleLevel + (quantity > 1 ? " ×" + quantity : "");
                case BOOK -> "Enchanted book · " + enchantments.getFirst().label();
                case GEAR -> readableName(item.token) + " · " + enchantments.stream()
                        .map(EnchantedLevel::label).collect(Collectors.joining(", "));
            };
        }
    }

    static final List<String> BOTTLE_LEVELS = List.of("III", "IV", "V");
    private static final List<String> ROMAN = List.of("", "I", "II", "III", "IV", "V");
    private static final Pattern STACK = Pattern.compile("([a-z_]+) x([1-9]\\d*)");
    private static final Pattern BOTTLE = Pattern.compile("ominous_bottle (III|IV|V)(?: x([1-9]\\d*))?");
    private static final Pattern ENCHANTED = Pattern.compile("([a-z_]+)\\[([^]]+)\\]");
    private static final Pattern ENCHANT_LEVEL = Pattern.compile("([a-z_]+) ([1-5])");

    private VaultLoot() {}

    static Optional<Entry> parse(String token) {
        token = token.trim();
        var stack = STACK.matcher(token);
        var bottle = BOTTLE.matcher(token);
        var gear = ENCHANTED.matcher(token);
        try {
            if (bottle.matches()) {
                int count = bottle.group(2) == null ? 1 : Integer.parseInt(bottle.group(2));
                return Optional.of(new Entry(Item.OMINOUS_BOTTLE, count, bottle.group(1), List.of()));
            }
            if (stack.matches()) {
                int count = Integer.parseInt(stack.group(2));
                return Arrays.stream(Item.values()).filter(item -> item.kind == Kind.STACK && item.token.equals(stack.group(1)))
                        .findFirst().map(item -> new Entry(item, count, "", List.of()));
            }
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        if (token.equals("book[wind_burst]")) return Optional.of(new Entry(Item.WIND_BURST_BOOK, 1, "", List.of()));
        if (gear.matches()) return parseEnchantments(gear.group(1), gear.group(2));

        for (Item item : Item.values()) {
            if (item.kind == Kind.PLAIN && item.token.equals(token)) return Optional.of(new Entry(item, 1, "", List.of()));
        }
        return Optional.empty();
    }

    private static Optional<Entry> parseEnchantments(String itemToken, String effects) {
        String[] tokens = effects.split(",", -1);
        for (Item item : Item.values()) {
            if (!item.token.equals(itemToken) || item.enchantments.isEmpty()) continue;
            if (item.kind == Kind.BOOK && tokens.length != 1) continue;
            List<EnchantedLevel> found = Arrays.stream(tokens).map(token -> parseLevel(item, token.trim())).toList();
            if (found.stream().noneMatch(Objects::isNull)
                    && found.stream().map(EnchantedLevel::enchantment).distinct().count() == found.size()) {
                return Optional.of(new Entry(item, 1, "", found));
            }
        }
        return Optional.empty();
    }

    private static EnchantedLevel parseLevel(Item item, String token) {
        var match = ENCHANT_LEVEL.matcher(token);
        if (!match.matches()) return null;
        int level = Integer.parseInt(match.group(2));
        for (Enchantment enchantment : item.enchantments) {
            if (enchantment.name().equals(match.group(1)) && level <= enchantment.maxLevel()) {
                return new EnchantedLevel(enchantment, level);
            }
        }
        return null;
    }

    static List<String> split(String line) {
        return Arrays.stream(line.split(";")).map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    static String display(String token) {
        return parse(token).map(Entry::label).orElse(token);
    }

    static String roman(int level) { return ROMAN.get(level); }

    private static List<String> searchTerms(String query) {
        return Arrays.stream(query.toLowerCase(Locale.ROOT).replace('_', ' ').trim().split("\\s+"))
                .filter(term -> !term.isEmpty()).toList();
    }

    private static String readableName(String name) {
        String label = name.replace('_', ' ');
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }
}
