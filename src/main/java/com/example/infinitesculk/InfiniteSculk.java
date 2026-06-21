package com.example.infinitesculk;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.SculkCatalyst;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SculkBloomEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InfiniteSculk extends JavaPlugin implements Listener, CommandExecutor {

    private boolean isEnabled;
    private boolean bypassXp;
    private int spreadSpeed;
    private int chargePower;

    /**
     * Cache: leagă locația unui catalizator (cheie = coordonatele lui ca string) de instanța
     * SculkCatalyst, ca să nu mai trebuiască să scanăm blocuri din jur de fiecare dată.
     * Populat o singură dată per catalizator, la primul SculkBloomEvent din zona lui.
     */
    private final Map<String, SculkCatalyst> catalystCache = new ConcurrentHashMap<>();

    /**
     * Reține, pentru fiecare bloc proaspăt convertit în sculk, cheia catalizatorului care l-a produs.
     * Așa propagăm sursa corectă mai departe fără să mai căutăm niciodată din nou.
     */
    private final Map<Block, String> blockToCatalystKey = new ConcurrentHashMap<>();

    /**
     * Cooldown per catalizator (in milisecunde), ca să nu reinjectăm bloom-uri mai des decât
     * o dată la N milisecunde per catalizator. Fără asta, un singur cursor activ poate genera
     * sute de re-bloom-uri pe secundă și sufoca serverul.
     */
    private final Map<String, Long> lastRebloomTime = new ConcurrentHashMap<>();
    private static final long REBLOOM_COOLDOWN_MS = 250;

    /**
     * Limită de siguranță: numărul maxim de re-bloom-uri consecutive permise per catalizator,
     * înainte de a lăsa charge-ul să se epuizeze natural. Previne o buclă teoretic infinită
     * dacă terenul din jur oferă blocuri sculk_replaceable la nesfârșit.
     */
    private final Map<String, Integer> rebloomCount = new ConcurrentHashMap<>();
    private int maxRebloomsPerCatalyst;

    private BukkitTask cleanupTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPluginConfig();
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("infinitesculk").setExecutor(this);

        // Curățăm periodic cache-urile, ca să nu crească la nesfârșit pe un server care rulează mult timp
        this.cleanupTask = getServer().getScheduler().runTaskTimer(this, this::cleanupStaleEntries, 20L * 60, 20L * 60);

        getLogger().info("InfiniteSculk v1.3.0 a fost pornit cu control de viteza, bypass XP si recharge optimizat!");
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        catalystCache.clear();
        blockToCatalystKey.clear();
        lastRebloomTime.clear();
        rebloomCount.clear();
    }

    private void loadPluginConfig() {
        reloadConfig();
        this.isEnabled = getConfig().getBoolean("enabled", true);
        this.bypassXp = getConfig().getBoolean("bypass-xp-requirement", true);
        this.spreadSpeed = getConfig().getInt("spread-speed-multiplier", 2);
        this.chargePower = getConfig().getInt("charge-power", 1000);
        this.maxRebloomsPerCatalyst = getConfig().getInt("max-reblooms-per-catalyst", 500);

        if (this.spreadSpeed < 1) this.spreadSpeed = 1;
        if (this.maxRebloomsPerCatalyst < 1) this.maxRebloomsPerCatalyst = 1;

        // SculkBloomEvent.setCharge() acceptă DOAR valori in intervalul [0, 1000].
        // Orice valoare in afara acestui interval arunca IllegalArgumentException
        // de fiecare data cand un mob moare langa un catalizator, blocand bloom-ul.
        if (this.chargePower > 1000) {
            getLogger().warning("charge-power din config.yml este " + this.chargePower
                    + ", dar API-ul Minecraft accepta maxim 1000. Valoarea a fost limitata la 1000.");
            this.chargePower = 1000;
        }
        if (this.chargePower < 0) {
            getLogger().warning("charge-power din config.yml este negativ. Valoarea a fost setata la 0.");
            this.chargePower = 0;
        }
    }

    /**
     * Calculeaza un charge sigur pentru setCharge(), clampat in intervalul [0, 1000]
     * acceptat de API. Folosit oriunde inmultim sau calculam charge-ul dinamic,
     * pentru a evita IllegalArgumentException la runtime.
     */
    private int clampCharge(int value) {
        if (value > 1000) return 1000;
        if (value < 0) return 0;
        return value;
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
            event.setCharge(clampCharge(chargePower));
        } else {
            event.setCharge(clampCharge(event.getCharge() * spreadSpeed));
        }

        // La momentul bloom-ului, blocul din eveniment este chiar catalizatorul care a pornit cursorul.
        // Îl prindem aici O SINGURĂ DATĂ și îl punem în cache, ca să nu mai trebuiască să-l căutăm
        // ulterior prin scanare de blocuri la fiecare conversie individuală.
        Block catalystBlock = event.getBlock();
        if (catalystBlock.getType() == Material.SCULK_CATALYST) {
            String key = blockKey(catalystBlock);
            catalystCache.put(key, (SculkCatalyst) catalystBlock.getState());
            rebloomCount.put(key, 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSculkSpread(BlockSpreadEvent event) {
        if (!isEnabled) return;
        Block source = event.getSource();
        Material sourceType = source.getType();

        // Verificăm dacă blocul sursă aparține familiei de Sculk (sculk, vein sau catalizator)
        if (sourceType != Material.SCULK_CATALYST && sourceType != Material.SCULK && sourceType != Material.SCULK_VEIN) {
            return;
        }

        Block newlyConverted = event.getBlock();

        // Ne interesează doar conversiile reale în SCULK (nu vein, nu altceva)
        if (newlyConverted.getType() != Material.SCULK) {
            return;
        }

        // Determinăm cheia catalizatorului responsabil pentru acest lanț de conversii.
        // Întâi verificăm dacă sursa directă e deja un bloc pe care l-am marcat anterior
        // (propagare fără scanare). Dacă sursa e chiar catalizatorul, folosim cheia lui direct.
        String catalystKey;
        if (sourceType == Material.SCULK_CATALYST) {
            catalystKey = blockKey(source);
        } else {
            catalystKey = blockToCatalystKey.get(source);
        }

        if (catalystKey == null) {
            // Nu avem nicio referință cunoscută pentru acest lanț (de exemplu, sculk preexistent
            // de dinainte de pornirea pluginului). Nu facem nimic — fără cache, nu riscăm scanare.
            return;
        }

        SculkCatalyst catalyst = catalystCache.get(catalystKey);
        if (catalyst == null) {
            return;
        }

        // Propagăm cheia catalizatorului către noul bloc, ca lanțul să poată continua mai departe
        // fără nicio scanare, oricât de departe s-ar extinde sculk-ul.
        blockToCatalystKey.put(newlyConverted, catalystKey);

        // Verificăm dacă acest bloc chiar reprezintă o "frontieră" — adică are în jurul lui
        // blocuri care încă pot fi convertite. Dacă e deja complet înconjurat de sculk/aer/blocuri
        // neconvertibile, nu are sens să reinjectăm charge aici: cursorul ar rătăci aiurea prin
        // teren deja transformat în loc să împingă răspândirea mai departe. Asta evită exact
        // comportamentul de "cursor care se plimbă fără rost prin sculk vechi".
        if (!hasConvertibleNeighbor(newlyConverted)) {
            return;
        }

        // Verificăm limita de siguranță, ca să nu reinjectăm la nesfârșit fără control
        int count = rebloomCount.getOrDefault(catalystKey, 0);
        if (count >= maxRebloomsPerCatalyst) {
            return;
        }

        // Verificăm cooldown-ul, ca să nu trimitem zeci de bloom-uri pe tick pentru același catalizator
        long now = System.currentTimeMillis();
        long lastTime = lastRebloomTime.getOrDefault(catalystKey, 0L);
        if (now - lastTime < REBLOOM_COOLDOWN_MS) {
            return;
        }

        // Toate verificările au trecut: re-alimentăm cursorul cu charge maxim, plecând chiar
        // din blocul proaspăt convertit, care am confirmat că e o frontieră reală. Charge-ul
        // practic nu se mai termină cât timp tot reușește să convertească blocuri noi, fără
        // nicio scanare costisitoare a hărții și fără să rătăcească prin teren deja transformat.
        catalyst.bloom(newlyConverted, chargePower);
        lastRebloomTime.put(catalystKey, now);
        rebloomCount.put(catalystKey, count + 1);
    }

    /**
     * Verifică dacă blocul dat are cel puțin un vecin direct (cele 6 fețe) care poate fi
     * convertit în sculk — adică e aer, apă, sau un bloc natural neconvertit încă. Folosit
     * ca să identificăm doar blocurile aflate la marginea reală a răspândirii, nu cele
     * deja complet înconjurate de sculk.
     */
    private boolean hasConvertibleNeighbor(Block block) {
        Block[] neighbors = new Block[] {
                block.getRelative(1, 0, 0),
                block.getRelative(-1, 0, 0),
                block.getRelative(0, 1, 0),
                block.getRelative(0, -1, 0),
                block.getRelative(0, 0, 1),
                block.getRelative(0, 0, -1)
        };

        for (Block neighbor : neighbors) {
            Material type = neighbor.getType();

            // Deja sculk sau catalizator -> nu e o frontieră utilă, continuăm căutarea
            if (type == Material.SCULK || type == Material.SCULK_VEIN || type == Material.SCULK_CATALYST) {
                continue;
            }

            // Aer și apă pot primi sculk vein, deci sunt frontiere valide
            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.WATER) {
                return true;
            }

            // Orice alt bloc solid natural (piatră, pământ, deepslate etc.) e potențial
            // convertibil prin tag-ul nativ sculk_replaceable verificat de motorul de joc.
            // Nu reimplementăm acel tag aici; presupunem optimist că blocurile solide
            // necunoscute sunt candidate valide, lăsând motorul nativ să decidă efectiv.
            return true;
        }

        return false;
    }

    /**
     * Construiește o cheie unică text pentru un bloc, pe baza lumii și coordonatelor sale.
     * Folosită ca identificator stabil pentru hărțile de cache.
     */
    private String blockKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    /**
     * Curăță periodic intrările vechi din cache-uri, ca să nu crească la nesfârșit memoria
     * folosită pe un server care rulează mult timp cu mult sculk activ.
     */
    private void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        long staleThreshold = 5 * 60 * 1000L; // 5 minute fără activitate = considerat inactiv

        lastRebloomTime.entrySet().removeIf(entry -> (now - entry.getValue()) > staleThreshold);

        // Eliminăm din cache-ul de catalizatori orice cheie care nu mai are activitate recentă
        catalystCache.keySet().removeIf(key -> !lastRebloomTime.containsKey(key) && !rebloomCount.containsKey(key));

        // blockToCatalystKey poate crește mult pe servere cu mult sculk; îl limităm dacă devine prea mare
        if (blockToCatalystKey.size() > 50000) {
            getLogger().info("Cache-ul de blocuri Sculk a depasit 50000 de intrari, se curata pentru a economisi memorie.");
            blockToCatalystKey.clear();
        }
    }
}
