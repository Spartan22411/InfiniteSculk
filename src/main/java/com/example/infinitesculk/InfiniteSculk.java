package com.example.infinitesculk;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SculkBloomEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class InfiniteSculk extends JavaPlugin implements Listener, CommandExecutor {

    private boolean isEnabled;
    private boolean bypassXp;
    private int spreadSpeed;
    private int chargePower;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPluginConfig();
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("infinitesculk").setExecutor(this);
        getLogger().info("InfiniteSculk v1.2.0 a fost pornit cu control de viteza si bypass XP!");
    }

    private void loadPluginConfig() {
        reloadConfig();
        this.isEnabled = getConfig().getBoolean("enabled", true);
        this.bypassXp = getConfig().getBoolean("bypass-xp-requirement", true);
        this.spreadSpeed = getConfig().getInt("spread-speed-multiplier", 2);
        this.chargePower = getConfig().getInt("charge-power", 1000);

        if (this.spreadSpeed < 1) this.spreadSpeed = 1;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("infinitesculk.admin")) {
                sender.sendMessage(ChatColor.RED + "Nu ai permisiuni!");
                return true;
            }
            loadPluginConfig();
            sender.sendMessage(ChatColor.GREEN + "[InfiniteSculk] Setarile au fost reincarcate cu succes!");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Foloseste: /infinitesculk reload");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSculkBloom(SculkBloomEvent event) {
        if (!isEnabled) return;

        // Ignoră complet valoarea originală de XP lăsată de mob și injectează direct puterea maximă
        if (bypassXp) {
            event.setCharge(chargePower);
        } else {
            event.setCharge(event.getCharge() * spreadSpeed);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSculkSpread(BlockSpreadEvent event) {
        if (!isEnabled) return;
        Block source = event.getSource();
        Material sourceType = source.getType();

        // Verificăm dacă blocul sursă aparține familiei de Sculk
        if (sourceType == Material.SCULK_CATALYST || sourceType == Material.SCULK || sourceType == Material.SCULK_VEIN) {

            // Dacă se dorește bypass total XP, transformăm instant destinația în catalizator activ sau sculk block
            if (event.getBlock().getType() == Material.SCULK_CATALYST) {
                org.bukkit.block.SculkCatalyst catalyst = (org.bukkit.block.SculkCatalyst) event.getBlock().getState();

                // Multiplicăm execuția în funcție de viteza aleasă în config
                for (int i = 0; i < spreadSpeed; i++) {
                    catalyst.bloom(event.getBlock(), chargePower);
                }
            }
        }
    }
}
