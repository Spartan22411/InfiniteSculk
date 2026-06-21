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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InfiniteSculk extends JavaPlugin implements CommandExecutor {

    private boolean isEnabled;
    private int searchRadius;
    private int blocksPerTick;
    private int taskInterval;
    private boolean requireCatalyst;

    private BukkitTask spreadTask;

    // Cele 6 directii ortogonale (fetele unui bloc).
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
        getLogger().info("InfiniteSculk v3.0.0 a fost pornit! Extindere vanilla: vein -> sculk, cu catalizator conectat.");
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
        this.requireCatalyst = getConfig().getBoolean("require-connected-catalyst", true);

        if (this.searchRadius < 4) this.searchRadius = 4;
        if (this.searchRadius > 100) this.searchRadius = 100;
        if (this.blocksPerTick < 1) this.blocksPerTick = 1;
        if (this.taskInterval < 1) this.taskInterval = 1;
    }

    private void startSpreadTask() {
        stopSpreadTask();
        if (!isEnabled) return;
        this.spreadTask = getServer().getScheduler().runTaskTimer(
                this, this::runSpreadStep, taskInterval, taskInterval);
    }

    private void stopSpreadTask() {
        if (spreadTask != null) {
            spreadTask.cancel();
            spreadTask = null;
        }
    }

    private void runSpreadStep() {
        int budget = blocksPerTick;
        for (Player player : getServer().getOnlinePlayers()) {
            if (budget <= 0) break;
            budget = spreadAroundPlayer(player, budget);
        }
    }

    /**
     * Cauta blocuri de sculk in jurul jucatorului care apartin unei structuri ce are un catalizator
     * conectat (adiacent), si extinde din ele respectand algoritmul vanilla.
     */
    private int spreadAroundPlayer(Player player, int budget) {
        Block center = player.getLocation().getBlock();

        List<Block> sculkBlocks = new ArrayList<>();
        int r = searchRadius;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    Block block = center.getRelative(x, y, z);
                    if (block.getType() == Material.SCULK) {
                        sculkBlocks.add(block);
                    }
                }
            }
        }

        if (sculkBlocks.isEmpty()) return budget;

        Collections.shuffle(sculkBlocks);

        for (Block sculkBlock : sculkBlocks) {
            if (budget <= 0) break;

            // Conditie globala: extindem doar daca acest bloc de sculk apartine unei structuri
            // care are macar un sculk catalyst lipit (adiacent) de ea undeva.
            if (requireCatalyst && !structureHasConnectedCatalyst(sculkBlock)) {
                continue;
            }

            budget = expandFrom(sculkBlock, budget);
        }

        return budget;
    }

    /**
     * Aplica algoritmul de extindere pornind dintr-un bloc de sculk, pe fiecare bloc candidat X
     * din vecinatate (un bloc solid convertibil sau orice bloc langa care vrem sa crestem):
     *
     *   1. Ne uitam la blocul de DEASUPRA lui X (X+UP). Putem pune acolo un sculk vein (lipit
     *      de fata de sus a lui X)? Daca deasupra lui X e ceva ce NU poate fi acoperit (bloc
     *      solid, alt sculk etc.) -> skip, trecem la urmatorul.
     *   2. Daca DA -> punem sculk vein deasupra lui X (pe fata DOWN a vein-ului, prins de X).
     *   3. Verificam daca X insusi poate fi inlocuit cu sculk block:
     *        - daca NU -> lasam vein-ul deasupra si trecem mai departe.
     *        - daca DA -> stergem vein-ul de deasupra SI inlocuim X cu sculk block.
     *
     * Returneaza bugetul ramas.
     */
    private int expandFrom(Block sculkBlock, int budget) {
        List<BlockFace> faces = new ArrayList<>(List.of(FACES));
        Collections.shuffle(faces);

        for (BlockFace face : faces) {
            if (budget <= 0) break;

            // Blocul candidat X = un vecin al blocului de sculk
            Block x = sculkBlock.getRelative(face);
            Material xType = x.getType();

            // X trebuie sa fie un bloc solid (ca sa aiba sens "deasupra" si "inlocuire").
            // Sarim peste aer, apa, si peste blocuri care sunt deja din familia sculk.
            if (xType == Material.SCULK || xType == Material.SCULK_CATALYST || xType == Material.SCULK_VEIN) {
                continue;
            }
            if (!xType.isSolid()) {
                continue;
            }

            // PASUL 1: blocul de deasupra lui X
            Block above = x.getRelative(BlockFace.UP);
            Material aboveType = above.getType();

            // Putem acoperi spatiul de deasupra cu un vein? (trebuie sa fie aer/apa, sau deja
            // un vein fara fata DOWN setata). Daca e bloc solid sau orice altceva -> skip.
            boolean canCover = (aboveType == Material.AIR || aboveType == Material.CAVE_AIR
                    || aboveType == Material.VOID_AIR || aboveType == Material.WATER
                    || aboveType == Material.SCULK_VEIN);
            if (!canCover) {
                // Deasupra lui X e ceva ce nu poate fi acoperit -> skip
                continue;
            }

            // Vein-ul de deasupra lui X se prinde de fata de SUS a lui X, adica fata DOWN a vein-ului.
            // Verificam ca X (cel de sub vein) e solid (este, am verificat mai sus) si ca fata e permisa.
            if (aboveType == Material.SCULK_VEIN) {
                BlockData aboveData = above.getBlockData();
                if (aboveData instanceof MultipleFacing && ((MultipleFacing) aboveData).hasFace(BlockFace.DOWN)) {
                    // vein-ul cu fata DOWN exista deja aici; tratam ca si cum l-am pus deja
                } else {
                    placeVeinFaceDown(above);
                    budget--;
                }
            } else {
                // PASUL 2: punem vein deasupra lui X
                placeVeinFaceDown(above);
                budget--;
            }

            if (budget < 0) budget = 0;

            // PASUL 3: poate X sa devina sculk block?
            if (Tag.SCULK_REPLACEABLE.isTagged(xType)) {
                // DA -> stergem vein-ul de deasupra si inlocuim X cu sculk
                if (above.getType() == Material.SCULK_VEIN) {
                    removeVeinFaceDown(above);
                }
                x.setType(Material.SCULK, true);
            }
            // NU -> lasam vein-ul deasupra (deja pus) si trecem mai departe
        }

        return budget;
    }

    /**
     * Pune un sculk vein in blocul dat, cu fata DOWN setata (vein prins de blocul de sub el),
     * pastrand eventualele fete existente daca era deja un vein.
     */
    private void placeVeinFaceDown(Block target) {
        MultipleFacing veinData;
        BlockData current = target.getBlockData();

        if (target.getType() == Material.SCULK_VEIN && current instanceof MultipleFacing) {
            veinData = (MultipleFacing) current;
        } else {
            veinData = (MultipleFacing) Material.SCULK_VEIN.createBlockData();
        }

        if (veinData.getAllowedFaces().contains(BlockFace.DOWN)) {
            veinData.setFace(BlockFace.DOWN, true);
            target.setBlockData(veinData, true);
        }
    }

    /**
     * Scoate fata DOWN a unui sculk vein. Daca dupa scoatere vein-ul nu mai are nicio fata,
     * blocul devine aer (vein-ul dispare complet).
     */
    private void removeVeinFaceDown(Block veinBlock) {
        BlockData current = veinBlock.getBlockData();
        if (!(current instanceof MultipleFacing)) {
            veinBlock.setType(Material.AIR, false);
            return;
        }

        MultipleFacing veinData = (MultipleFacing) current;
        veinData.setFace(BlockFace.DOWN, false);

        // Daca nu mai are nicio fata activa, vein-ul dispare
        if (veinData.getFaces().isEmpty()) {
            veinBlock.setType(Material.AIR, false);
        } else {
            veinBlock.setBlockData(veinData, true);
        }
    }

    /**
     * Verifica daca structura de sculk din care face parte blocul dat are un sculk catalyst
     * lipit (adiacent ortogonal) de oricare bloc al ei. Parcurge structura conectata (flood-fill)
     * prin blocuri de sculk si sculk vein, limitat ca dimensiune ca sa nu coste prea mult.
     */
    private boolean structureHasConnectedCatalyst(Block start) {
        Set<Block> visited = new HashSet<>();
        List<Block> queue = new ArrayList<>();
        queue.add(start);
        visited.add(start);

        int maxBlocks = 2000; // limita de siguranta pentru flood-fill
        int index = 0;

        while (index < queue.size()) {
            Block current = queue.get(index++);

            for (BlockFace face : FACES) {
                Block neighbor = current.getRelative(face);
                Material type = neighbor.getType();

                // Am gasit un catalizator lipit de structura -> conexiune confirmata
                if (type == Material.SCULK_CATALYST) {
                    return true;
                }

                // Continuam flood-fill-ul doar prin blocuri din familia sculk (block + vein)
                if ((type == Material.SCULK || type == Material.SCULK_VEIN) && !visited.contains(neighbor)) {
                    if (visited.size() >= maxBlocks) {
                        // Structura e prea mare ca s-o parcurgem integral; nu blocam extinderea
                        // doar pentru ca n-am terminat cautarea — presupunem ca e conectata.
                        return true;
                    }
                    visited.add(neighbor);
                    queue.add(neighbor);
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
            startSpreadTask();
            sender.sendMessage("\u00a7a[InfiniteSculk] Setarile au fost reincarcate cu succes!");
            return true;
        }
        sender.sendMessage("\u00a7eFoloseste: /infinitesculk reload");
        return true;
    }
}
