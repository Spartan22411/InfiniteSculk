package com.example.infinitesculk;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SculkBloomEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class InfiniteSculk extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSculkBloom(SculkBloomEvent event) {
        event.setCharge(1000); 
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSculkSpread(BlockSpreadEvent event) {
        Block source = event.getSource();
        Material sourceType = source.getType();
        if (sourceType == Material.SCULK_CATALYST || sourceType == Material.SCULK || sourceType == Material.SCULK_VEIN) {
            if (event.getBlock().getType() == Material.SCULK_CATALYST) {
                org.bukkit.block.SculkCatalyst catalyst = (org.bukkit.block.SculkCatalyst) event.getBlock().getState();
                catalyst.bloom(event.getBlock(), 1000);
            }
        }
    }
}
