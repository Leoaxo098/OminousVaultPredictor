package dev.leo.vaultpredictor;

import java.util.ArrayList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potions;

final class VaultItemStack {
    private VaultItemStack() {}

    static String token(ItemStack stack) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals("minecraft")) return null;
        String name = id.getPath();
        int count = stack.getCount();
        String token = switch (name) {
            case "emerald", "diamond", "wind_charge" -> name + " x" + count;
            case "tipped_arrow" -> {
                var potion = stack.get(DataComponents.POTION_CONTENTS);
                yield potion != null && potion.potion().filter(Potions.STRONG_SLOWNESS::equals).isPresent()
                        ? "tipped_arrow_slowness x" + count : null;
            }
            case "ominous_bottle" -> {
                var amplifier = stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
                yield amplifier != null && amplifier.value() >= 2 && amplifier.value() <= 4
                        ? name + " " + VaultLoot.roman(amplifier.value() + 1) + (count == 1 ? "" : " x" + count)
                        : null;
            }
            case "crossbow", "diamond_axe", "diamond_chestplate", "enchanted_book" -> enchanted(stack, name);
            default -> count == 1 ? name : null;
        };
        return token != null && VaultLoot.parse(token).isPresent() ? token : null;
    }

    private static String enchanted(ItemStack stack, String name) {
        if (stack.getCount() != 1) return null;
        boolean book = name.equals("enchanted_book");
        var enchantments = stack.get(book ? DataComponents.STORED_ENCHANTMENTS : DataComponents.ENCHANTMENTS);
        if (enchantments == null) return null;
        var entries = new ArrayList<String>();
        for (var entry : enchantments.entrySet()) {
            var key = entry.getKey().unwrapKey();
            if (key.isEmpty() || !key.get().identifier().getNamespace().equals("minecraft")) return null;
            entries.add(key.get().identifier().getPath() + " " + entry.getIntValue());
        }
        if (book && entries.equals(java.util.List.of("wind_burst 1"))) return "book[wind_burst]";
        entries.sort(String::compareTo);
        return (book ? "book" : name) + "[" + String.join(",", entries) + "]";
    }
}
