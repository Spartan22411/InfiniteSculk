package com.example.infinitesculk;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
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
     * Pentru un bloc de sculk dat, parcurge vecinii in ordine aleatorie si incearca sa-i extinda
     * RESPECTAND mecanica vanilla:
     *   1. Daca vecinul e aer/apa -> creste un sculk vein pe fata dinspre blocul sursa (ca lichenul).
     *   2. Daca vecinul e un bloc solid convertibil (tag SCULK_REPLACEABLE) -> il transforma in
     *      sculk block, apoi creste sculk vein-uri pe fetele lui expuse la aer (exact ca vanilla).
     * Returneaza bugetul ramas.
     */
    private int convertNeighbors(Block sculkBlock, int budget) {
        List<BlockFace> faces = new ArrayList<>(Arrays.asList(FACES.clone()));
        Collections.shuffle(faces);

        for (BlockFace face : faces) {
            if (budget <= 0) break;

            Block neighbor = sculkBlock.getRelative(face);
            Material type = neighbor.getType();

            // Sarim peste tot ce e deja din familia sculk
            if (type == Material.SCULK || type == Material.SCULK_VEIN || type == Material.SCULK_CATALYST) {
                continue;
            }

            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR || type == Material.WATER) {
                // Spatiu gol: vanilla pune un sculk vein pe fata dinspre blocul de sculk sursa.
                if (placeVeinFace(neighbor, face.getOppositeFace())) {
                    budget--;
                }
            } else if (Tag.SCULK_REPLACEABLE.isTagged(type)) {
                // Bloc solid convertibil: il facem sculk block, apoi crestem vein pe fetele expuse.
                neighbor.setType(Material.SCULK, true);
                budget--;
                growVeinsAround(neighbor, budget);
            }
        }

        return budget;
    }

    /**
     * Creste sculk vein-uri pe fetele expuse la aer ale unui bloc proaspat convertit in sculk,
     * exact ca in vanilla (unde un nou sculk block incearca sa adauge vein pe blocurile adiacente).
     * Aici punem vein-urile pe blocurile de aer din jur, orientate spre noul bloc de sculk.
     */
    private void growVeinsAround(Block sculkBlock, int budget) {
        for (BlockFace face : FACES) {
            if (budget <= 0) break;

            Block neighbor = sculkBlock.getRelative(face);
            Material type = neighbor.getType();

            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR || type == Material.WATER) {
                placeVeinFace(neighbor, face.getOppositeFace());
            }
        }
    }

    /**
     * Plaseaza un sculk vein in blocul dat (care trebuie sa fie aer/apa), cu textura pe fata
     * indicata. Daca blocul e deja sculk vein, doar adauga fata respectiva la cele existente.
     * Returneaza true daca s-a modificat ceva.
     */
    private boolean placeVeinFace(Block target, BlockFace face) {
        Material type = target.getType();

        boolean isReplaceableForVein = (type == Material.AIR || type == Material.CAVE_AIR
                || type == Material.VOID_AIR || type == Material.WATER || type == Material.SCULK_VEIN);
        if (!isReplaceableForVein) {
            return false;
        }

        // Sculk vein se ataseaza pe fata unui bloc solid; daca blocul de care ar trebui sa se
        // prinda (in directia 'face') nu e solid, vein-ul nu poate sta acolo.
        Block attachTo = target.getRelative(face);
        if (!attachTo.getType().isSolid()) {
            return false;
        }

        BlockData currentData = target.getBlockData();
        MultipleFacing veinData;

        if (type == Material.SCULK_VEIN && currentData instanceof MultipleFacing) {
            // Pastram fetele existente si adaugam noua fata
            veinData = (MultipleFacing) currentData;
            if (veinData.hasFace(face)) {
                return false; // fata exista deja, nimic de facut
            }
        } else {
            veinData = (MultipleFacing) Material.SCULK_VEIN.createBlockData();
        }

        if (!veinData.getAllowedFaces().contains(face)) {
            return false;
        }

        veinData.setFace(face, true);
        target.setBlockData(veinData, true);
        return true;
    }

    /**
     * Verifica daca un bloc de sculk are macar un vecin care merita procesat — fie un bloc
     * solid convertibil in sculk, fie un spatiu de aer/apa unde poate creste un sculk vein.
     */
    private boolean hasConvertibleNeighbor(Block block) {
        for (BlockFace face : FACES) {
            Block neighbor = block.getRelative(face);
            Material type = neighbor.getType();

            // Bloc solid care poate deveni sculk
            if (Tag.SCULK_REPLACEABLE.isTagged(type)) {
                return true;
            }

            // Spatiu gol unde ar putea creste un vein (daca exista un bloc solid de care sa se prinda)
            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR || type == Material.WATER) {
                if (canAnyVeinAttach(neighbor)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Verifica daca intr-un bloc de aer/apa ar putea sta un sculk vein, adica daca are macar
     * o fata adiacenta catre un bloc solid de care vein-ul sa se poata prinde, fata care inca
     * nu e acoperita de vein.
     */
    private boolean canAnyVeinAttach(Block airBlock) {
        BlockData data = airBlock.getBlockData();
        MultipleFacing existing = (airBlock.getType() == Material.SCULK_VEIN && data instanceof MultipleFacing)
                ? (MultipleFacing) data : null;

        for (BlockFace face : FACES) {
            if (airBlock.getRelative(face).getType().isSolid()) {
                if (existing == null || !existing.hasFace(face)) {
                    return true;
                }
            }
        }
        return false;
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
