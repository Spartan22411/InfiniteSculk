package com.example.infinitesculk;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class InfiniteSculk extends JavaPlugin implements CommandExecutor {

    private boolean isEnabled;
    private int searchRadius;
    private int blocksPerTick;
    private int taskInterval;

    private BukkitTask spreadTask;

    // Cele 6 directii ortogonale (fetele unui bloc), folosite pentru extinderea bloc-cu-bloc.
    private static final BlockFace[] FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPluginConfig();
        this.getCommand("infinitesculk").setExecutor(this);
        startSpreadTask();
        getLogger().info("InfiniteSculk v2.0.0 a fost pornit! Sculk-ul se extinde acum bloc-cu-bloc, automat.");
    }

    @Override
    public void onDisable() {
        stopSpreadTask();
    }

    private void loadPluginConfig() {
        reloadConfig();
        this.isEnabled = getConfig().getBoolean("enabled", true);
        this.searchRadius = getConfig().getInt("search-radius", 40);
        this.blocksPerTick = getConfig().getInt("blocks-per-tick", 64);
        this.taskInterval = getConfig().getInt("task-interval-ticks", 2);

        // Validari de siguranta, ca valori absurde din config sa nu strice serverul
        if (this.searchRadius < 4) this.searchRadius = 4;
        if (this.searchRadius > 100) this.searchRadius = 100;
        if (this.blocksPerTick < 1) this.blocksPerTick = 1;
        if (this.taskInterval < 1) this.taskInterval = 1;
    }

    private void startSpreadTask() {
        stopSpreadTask();
        if (!isEnabled) return;

        // Rulam periodic pe thread-ul principal (modificarile de blocuri TREBUIE facute acolo).
        this.spreadTask = getServer().getScheduler().runTaskTimer(
                this, this::runSpreadStep, taskInterval, taskInterval);
    }

    private void stopSpreadTask() {
        if (spreadTask != null) {
            spreadTask.cancel();
            spreadTask = null;
        }
    }

    /**
     * Un pas de extindere: pentru fiecare jucator online, cautam blocuri de sculk in jurul lui
     * care au cel putin un vecin neconvertit, si convertim acel vecin direct in sculk. Limitam
     * numarul total de conversii per pas (blocksPerTick) ca sa nu apese serverul.
     */
    private void runSpreadStep() {
        int budget = blocksPerTick;

        for (Player player : getServer().getOnlinePlayers()) {
            if (budget <= 0) break;
            budget = spreadAroundPlayer(player, budget);
        }
    }

    /**
     * Cauta blocuri de sculk in jurul unui jucator si extinde din ele in blocurile neconvertite
     * vecine. Returneaza bugetul ramas dupa conversii.
     */
    private int spreadAroundPlayer(Player player, int budget) {
        World world = player.getWorld();
        Block center = player.getLocation().getBlock();

        // Adunam intai toate frontierele candidate (blocuri sculk cu macar un vecin convertibil),
        // apoi convertim. Amestecam lista ca extinderea sa para naturala, nu mereu in aceeasi directie.
        List<Block> frontier = new ArrayList<>();

        int r = searchRadius;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    Block block = center.getRelative(x, y, z);

                    // Ne intereseaza doar blocurile care SUNT deja sculk (sursa de extindere)
                    if (block.getType() != Material.SCULK) continue;

                    // Verificam rapid daca are macar un vecin convertibil; daca da, e o frontiera
                    if (hasConvertibleNeighbor(block)) {
                        frontier.add(block);
                    }
                }
            }
        }

        if (frontier.isEmpty()) return budget;

        Collections.shuffle(frontier);

        for (Block sculkBlock : frontier) {
            if (budget <= 0) break;
            budget = convertNeighbors(sculkBlock, budget);
        }

        return budget;
    }

    /**
     * Converteste in sculk vecinii neconvertiti ai unui bloc dat, in ordine aleatorie,
     * pana la epuizarea bugetului. Returneaza bugetul ramas.
     */
    private int convertNeighbors(Block sculkBlock, int budget) {
        List<BlockFace> faces = Arrays.asList(FACES.clone());
        Collections.shuffle(faces);

        for (BlockFace face : faces) {
            if (budget <= 0) break;

            Block neighbor = sculkBlock.getRelative(face);
            if (isConvertible(neighbor)) {
                neighbor.setType(Material.SCULK, true);
                budget--;
            }
        }

        return budget;
    }

    /**
     * Verifica daca un bloc are cel putin un vecin care poate fi convertit in sculk.
     */
    private boolean hasConvertibleNeighbor(Block block) {
        for (BlockFace face : FACES) {
            if (isConvertible(block.getRelative(face))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Un bloc e convertibil daca face parte din tag-ul oficial vanilla SCULK_REPLACEABLE
     * (piatra, pamant, iarba, deepslate etc.) si nu e deja un bloc din familia sculk.
     */
    private boolean isConvertible(Block block) {
        Material type = block.getType();
        if (type == Material.SCULK || type == Material.SCULK_VEIN || type == Material.SCULK_CATALYST) {
            return false;
        }
        return Tag.SCULK_REPLACEABLE.isTagged(type);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("infinitesculk.admin")) {
                sender.sendMessage("\u00a7cNu ai permisiuni!");
                return true;
            }
            loadPluginConfig();
            startSpreadTask(); // repornim task-ul cu noile setari
            sender.sendMessage("\u00a7a[InfiniteSculk] Setarile au fost reincarcate cu succes!");
            return true;
        }
        sender.sendMessage("\u00a7eFoloseste: /infinitesculk reload");
        return true;
    }
}
