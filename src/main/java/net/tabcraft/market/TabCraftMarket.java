package net.tabcraft.market;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class TabCraftMarket extends JavaPlugin implements Listener {
    private Economy economy;
    private NamespacedKey actionKey, categoryKey, itemKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        migrateOldConfig();
        actionKey = new NamespacedKey(this, "action");
        categoryKey = new NamespacedKey(this, "category");
        itemKey = new NamespacedKey(this, "item");
        setupEconomy();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("TabCraftMarket aktif. Komutlar: /shop ve /sipariş");
    }

    // Eski sürümün config.yml dosyası sunucuda kaldıysa yeni ikon ve ürün listelerini uygula.
    private void migrateOldConfig() {
        if (getConfig().getInt("config-version", 1) >= 3) return;
        try (InputStreamReader reader = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            getConfig().set("categories.pvp.icon", "TOTEM_OF_UNDYING");
            for (String id : defaults.getConfigurationSection("categories").getKeys(false)) {
                String itemsPath = "categories." + id + ".items";
                if (getConfig().getInt("config-version", 1) < 3) {
                    getConfig().set(itemsPath, defaults.getList(itemsPath));
                }
            }
            getConfig().set("config-version", 3);
            saveConfig();
            getLogger().info("Market config.yml yeni sürüme yükseltildi.");
        } catch (Exception exception) {
            getLogger().warning("Market config yükseltilemedi: " + exception.getMessage());
        }
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault bulunamadı; alış/satış kapalı.");
            return;
        }
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider != null) economy = provider.getProvider();
        if (economy == null) getLogger().warning("Vault ekonomi sağlayıcısı bulunamadı.");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut yalnızca oyuncular içindir.");
            return true;
        }
        openMain(player);
        return true;
    }

    private void openMain(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, color(getConfig().getString("menu-title", "&8SHOP")));
        if (!getConfig().isConfigurationSection("categories")) return;
        for (String id : getConfig().getConfigurationSection("categories").getKeys(false)) {
            String path = "categories." + id;
            int slot = getConfig().getInt(path + ".slot", 13);
            if (slot < 0 || slot >= inv.getSize()) continue;
            Material type = Material.matchMaterial(getConfig().getString(path + ".icon", "CHEST"));
            if (type == null) type = Material.CHEST;
            ItemStack icon = new ItemStack(type);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(color(getConfig().getString(path + ".name", id)));
            meta.setLore(List.of(color(getConfig().getString(path + ".description", "&7Eşyaları görüntülemek için tıkla"))));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "category");
            meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, id);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
        }
        player.openInventory(inv);
    }

    private void openCategory(Player player, String id) {
        String path = "categories." + id;
        if (!getConfig().isConfigurationSection(path)) return;
        String category = getConfig().getString(path + ".name", id);
        String title = getConfig().getString("category-title", "&8SHOP &7» %category%")
                .replace("%category%", ChatColor.stripColor(color(category)));
        Inventory inv = Bukkit.createInventory(null, 27, color(title));
        List<Map<?, ?>> products = getConfig().getMapList(path + ".items");
        int slot = 0;
        for (int index = 0; index < products.size() && index < 9; index++) {
            Map<?, ?> product = products.get(index);
            Object rawMaterial = product.containsKey("material") ? product.get("material") : "STONE";
            Material material = Material.matchMaterial(String.valueOf(rawMaterial));
            if (material == null) {
                getLogger().warning("Geçersiz market eşyası: " + rawMaterial + " (" + path + ")");
                continue;
            }
            Object rawName = product.containsKey("name") ? product.get("name") : material.name();
            double buy = number(product.get("buy"));
            double sell = number(product.get("sell"));
            ItemStack display = new ItemStack(material);
            ItemMeta meta = display.getItemMeta();
            meta.setDisplayName(color(String.valueOf(rawName)));
            meta.setLore(List.of(
                    color("&7Satın al: &a" + money(buy) + "$"),
                    color("&7Sat: &c" + money(sell) + "$"),
                    color("&8 "),
                    color("&aSol tık &7» 1 adet satın al"),
                    color("&cSağ tık &7» 1 adet sat"),
                    color("&eShift + tık &7» 64 adet")
            ));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "product");
            meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, id);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.INTEGER, index);
            display.setItemMeta(meta);
            inv.setItem(9 + slot++, display);
        }
        if (slot == 0) player.sendMessage(color("&cBu kategoride henüz ürün bulunmuyor."));
        player.openInventory(inv);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        event.setCancelled(true);
        if ("category".equals(action)) {
            String id = meta.getPersistentDataContainer().get(categoryKey, PersistentDataType.STRING);
            if (id != null) Bukkit.getScheduler().runTask(this, () -> openCategory(player, id));
        } else if ("back".equals(action)) {
            Bukkit.getScheduler().runTask(this, () -> openMain(player));
        } else if ("product".equals(action)) {
            transact(player, event, meta);
        }
    }

    private void transact(Player player, InventoryClickEvent event, ItemMeta meta) {
        if (economy == null) {
            player.sendMessage(color("&cEkonomi hazır değil. Vault ve ekonomi eklentisini kontrol et."));
            return;
        }
        String category = meta.getPersistentDataContainer().get(categoryKey, PersistentDataType.STRING);
        Integer index = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.INTEGER);
        if (category == null || index == null) return;
        List<Map<?, ?>> products = getConfig().getMapList("categories." + category + ".items");
        if (index < 0 || index >= products.size()) return;
        Map<?, ?> product = products.get(index);
        Material material = Material.matchMaterial(String.valueOf(product.get("material")));
        if (material == null) return;
        boolean buying = event.isLeftClick();
        int requested = event.isShiftClick() ? 64 : 1;
        double unitPrice = number(product.get(buying ? "buy" : "sell"));
        if (unitPrice <= 0) {
            player.sendMessage(color("&cBu işlem için fiyat tanımlanmamış."));
            return;
        }
        if (buying) {
            double total = unitPrice * requested;
            if (economy.getBalance(player) < total) {
                player.sendMessage(color("&cYeterli paran yok. Gerekli: &e" + money(total) + "$"));
                return;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(material, requested));
            int left = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            int added = requested - left;
            if (added <= 0) {
                player.sendMessage(color("&cEnvanterinde yer yok."));
                return;
            }
            double charge = unitPrice * added;
            economy.withdrawPlayer(player, charge);
            player.sendMessage(color("&a" + added + "x " + material.name() + " satın aldın. &7(-" + money(charge) + "$)"));
        } else {
            int owned = 0;
            for (ItemStack stack : player.getInventory().getStorageContents())
                if (stack != null && stack.getType() == material) owned += stack.getAmount();
            int amount = Math.min(requested, owned);
            if (amount <= 0) {
                player.sendMessage(color("&cSatacak eşyan yok."));
                return;
            }
            int remaining = amount;
            for (int slot = 0; slot < player.getInventory().getStorageContents().length && remaining > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack == null || stack.getType() != material) continue;
                int removed = Math.min(remaining, stack.getAmount());
                stack.setAmount(stack.getAmount() - removed);
                remaining -= removed;
                if (stack.getAmount() <= 0) player.getInventory().setItem(slot, null);
            }
            double payment = unitPrice * amount;
            economy.depositPlayer(player, payment);
            player.sendMessage(color("&a" + amount + "x " + material.name() + " sattın. &7(+" + money(payment) + "$)"));
        }
    }

    private double number(Object value) {
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return 0.0; }
    }
    private String money(double value) {
        return String.format(java.util.Locale.US, "%.2f", value).replaceAll("\\.?0+$", "");
    }
    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}