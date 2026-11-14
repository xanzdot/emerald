package com.emeraldtools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EmeraldToolsPlugin extends JavaPlugin implements Listener {

    private static final int MAX_TREE_BLOCKS = 200;
    private static final int TREE_RADIUS_XZ = 10;
    private static final int TREE_RADIUS_Y = 40;

    private NamespacedKey toolKey;
    // Set while we break extra blocks, so our own events don't trigger again.
    private boolean expanding = false;

    @Override
    public void onEnable() {
        toolKey = new NamespacedKey(this, "emerald_tool");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("emeraldtools").setExecutor(this);
        getCommand("nv").setExecutor(this);
        getLogger().info("EmeraldTools enabled.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (expanding) return;

        Player player = event.getPlayer();
        if (player.isSneaking()) return; // hold shift to mine just one block

        String type = toolType(player.getInventory().getItemInMainHand());
        if (type == null) return;

        expanding = true;
        try {
            switch (type) {
                case "pickaxe" -> breakThreeByThree(player, event.getBlock());
                case "axe" -> breakTree(player, event.getBlock());
                default -> { }
            }
        } finally {
            expanding = false;
        }
    }

    // Breaks the 8 blocks around the target, in the plane facing the player.
    private void breakThreeByThree(Player player, Block origin) {
        Vector dir = player.getLocation().getDirection();
        double ax = Math.abs(dir.getX());
        double ay = Math.abs(dir.getY());
        double az = Math.abs(dir.getZ());

        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;

                int dx, dy, dz;
                if (ay >= ax && ay >= az) {      // looking up/down: flat 3x3
                    dx = a; dy = 0; dz = b;
                } else if (ax >= az) {           // looking along X: wall in YZ plane
                    dx = 0; dy = a; dz = b;
                } else {                         // looking along Z: wall in XY plane
                    dx = a; dy = b; dz = 0;
                }

                ItemStack tool = player.getInventory().getItemInMainHand();
                if (!"pickaxe".equals(toolType(tool))) return;

                Block target = origin.getRelative(dx, dy, dz);
                if (!Tag.MINEABLE_PICKAXE.isTagged(target.getType())) continue;

                if (breakExtra(player, target, tool)) {
                    wearTool(player, 1);
                }
            }
        }
    }

    // Breaks every log connected to the one the player hit, up to a limit.
    private void breakTree(Player player, Block origin) {
        if (!Tag.LOGS.isTagged(origin.getType())) return;

        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        List<Block> logs = new ArrayList<>();
        seen.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && logs.size() < MAX_TREE_BLOCKS) {
            Block current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        Block next = current.getRelative(dx, dy, dz);
                        if (seen.contains(next)) continue;
                        seen.add(next);

                        if (Math.abs(next.getX() - origin.getX()) > TREE_RADIUS_XZ) continue;
                        if (Math.abs(next.getZ() - origin.getZ()) > TREE_RADIUS_XZ) continue;
                        if (Math.abs(next.getY() - origin.getY()) > TREE_RADIUS_Y) continue;
                        if (!Tag.LOGS.isTagged(next.getType())) continue;

                        if (logs.size() < MAX_TREE_BLOCKS) {
                            logs.add(next);
                            queue.add(next);
                        }
                    }
                }
            }
        }

        for (Block log : logs) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            if (!"axe".equals(toolType(tool))) return;

            if (breakExtra(player, log, tool)) {
                wearTool(player, 1);
            }
        }
    }

    // Fires a real BreakEvent so WorldGuard, claims and other protection plugins can block it.
    private boolean breakExtra(Player player, Block block, ItemStack tool) {
        BlockBreakEvent check = new BlockBreakEvent(block, player);
        Bukkit.getPluginManager().callEvent(check);
        if (check.isCancelled()) return false;

        block.breakNaturally(tool);
        return true;
    }

    // Extra blocks cost durability too, so the tool can't be abused for free mining.
    private void wearTool(Player player, int amount) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return;

        int newDamage = damageable.getDamage() + amount;
        if (newDamage >= tool.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(null);
            return;
        }

        damageable.setDamage(newDamage);
        tool.setItemMeta(meta);
        player.getInventory().setItemInMainHand(tool);
    }

    private String toolType(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    private ItemStack createTool(String type) {
        Material material = switch (type) {
            case "pickaxe" -> Material.DIAMOND_PICKAXE;
            case "axe" -> Material.DIAMOND_AXE;
            default -> Material.DIAMOND_SWORD;
        };
        String name = switch (type) {
            case "pickaxe" -> "Emerald Pickaxe";
            case "axe" -> "Emerald Axe";
            default -> "Emerald Sword";
        };

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GREEN));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("nv")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use /nv.");
                return true;
            }
            if (player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
                player.sendMessage("Night vision off.");
            } else {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                        PotionEffect.INFINITE_DURATION, 0, false, false, true));
                player.sendMessage("Night vision on.");
            }
            return true;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("Usage: /emeraldtools give <pickaxe|axe|sword> [player]");
            return true;
        }

        String type = args[1].toLowerCase();
        if (!type.equals("pickaxe") && !type.equals("axe") && !type.equals("sword")) {
            sender.sendMessage("Unknown tool. Use pickaxe, axe, or sword.");
            return true;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("Specify a player.");
            return true;
        }

        if (target == null) {
            sender.sendMessage("That player is not online.");
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createTool(type));
        leftover.values().forEach(item ->
                target.getWorld().dropItemNaturally(target.getLocation(), item));

        sender.sendMessage("Gave " + type + " to " + target.getName() + ".");
        return true;
    }
}
