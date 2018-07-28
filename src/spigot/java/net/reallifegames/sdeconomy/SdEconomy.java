/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Tyler Bucher
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.reallifegames.sdeconomy;

import net.milkbowl.vault.economy.Economy;
import net.reallifegames.sdeconomy.commands.*;
import net.reallifegames.sdeconomy.inventory.InventoryUtility;
import net.reallifegames.sdeconomy.inventory.ItemListInventory;
import net.reallifegames.sdeconomy.listeners.InventoryClickListener;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

/**
 * The SdEconomy plugin main class.
 *
 * @author Tyler Bucher
 */
public class SdEconomy extends JavaPlugin {

    /**
     * The economy service provided by vault.
     */
    private Economy economyService;

    /**
     * The decimal formatter for printing money.
     */
    public DecimalFormat decimalFormat;

    /**
     * The configuration for this plugin.
     */
    private Configuration configuration;

    /**
     * Called when this plugin is enabled.
     */
    @Override
    public void onEnable() {
        // Setup the decimal formatter
        decimalFormat = new DecimalFormat(".####");
        decimalFormat.setRoundingMode(RoundingMode.DOWN);
        // Get the config
        configuration = new Configuration(this);
        // Get vault plugin
        if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
            this.getLogger().log(Level.SEVERE, "Vault plugin not found. Plugin not loaded");
            return;
        }
        // Get economy service
        final RegisteredServiceProvider<Economy> registeredServiceProvider = getServer().getServicesManager()
                .getRegistration(Economy.class);
        if (registeredServiceProvider == null) {
            this.getLogger().log(Level.SEVERE, "Economy service not found. Plugin not loaded");
            return;
        }
        economyService = registeredServiceProvider.getProvider();
        if (economyService == null) {
            this.getLogger().log(Level.SEVERE, "Economy service is null. Plugin not loaded");
            return;
        }
        // Load material names
        if (configuration.isPopulateDatabase()) {
            for (final Material material : Material.values()) {
                if (material.isItem()) {
                    Product.stockPrices.computeIfAbsent(material.name(), k->new Product(material.name().toLowerCase(), material.name()));
                }
            }
        }
        // Setup sql data
        try {
            SqlService.createConstantsTable(configuration.getJdbcUrl());
            SqlService.createProductTable(configuration.getJdbcUrl());
            SqlService.createUuidTable(configuration.getJdbcUrl());
            SqlService.createTransactionTable(configuration.getJdbcUrl());
            SqlService.setSqlVersion(configuration.getJdbcUrl());
            SqlService.updateToSqlV4(configuration.getJdbcUrl());
            SqlService.updateToSqlV5(configuration.getJdbcUrl());
            SqlService.readProductTable(configuration.getJdbcUrl(), Product.stockPrices);
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error accessing database. Plugin not loaded", e);
            return;
        }
        // Register commands
        registerCommands();
        // Create repeating save task
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, ()->{
            // Attempt to save item data
            try {
                SqlService.updateProductTable(configuration.getJdbcUrl(), Product.stockPrices);
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Error accessing database", e);
            }
        }, 0, configuration.getSaveInterval());
        // Create repeating decay task
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, ()->{
            try {
                // Connect to database
                final Connection sqlConnection = DriverManager.getConnection(configuration.getJdbcUrl());
                sqlConnection.setAutoCommit(false);
                final PreparedStatement insertStatement = sqlConnection.prepareStatement(SqlService.INSERT_TRANSACTION_TABLE_SQL);
                // Attempt decay product demand
                for (Product product : Product.stockPrices.values()) {
                    final int amount = Product.decayDemand(product, configuration.getDemandDecayAmount());
                    if (amount > 0) {
                        // Setup prepared statement
                        insertStatement.setString(1, SqlService.SYSTEM_UUID);
                        insertStatement.setByte(2, SqlService.DECAY_ACTION);
                        insertStatement.setString(3, product.alias);
                        insertStatement.setFloat(4, amount);
                        insertStatement.setDouble(5, 0);
                        // Execute query
                        insertStatement.addBatch();
                    }
                }
                // Close objects
                insertStatement.executeBatch();
                sqlConnection.commit();
                insertStatement.close();
                sqlConnection.close();
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Error accessing database", e);
            }
        }, configuration.getDemandDecayInterval(), configuration.getDemandDecayInterval());
        // Setup Inventory data
        ItemListInventory.addItemStacks(InventoryUtility.getItemStacksFromProducts(Product.stockPrices.values()));
        // Register event listeners
        this.getServer().getPluginManager().registerEvents(new InventoryClickListener(), this);
    }

    /**
     * Registers all of the commands this plugin uses.
     */
    private void registerCommands() {
        // Set price command
        this.getCommand("setprice").setExecutor(new SetPriceCommand(this));
        // Remove price command
        this.getCommand("removeprice").setExecutor(new RemovePriceCommand(this));
        // Get price command
        this.getCommand("getprice").setExecutor(new GetPriceCommand(this));
        // Check sell command
        this.getCommand("checksell").setExecutor(new CheckSellCommand(this));
        // Check buy command
        this.getCommand("checkbuy").setExecutor(new CheckBuyCommand(this));
        // Sell command
        this.getCommand("sell").setExecutor(new SellCommand(this));
        // Buy command
        this.getCommand("buy").setExecutor(new BuyCommand(this));
        // Transaction command
        this.getCommand("transactions").setExecutor(new TransactionCommand(this));
        // Version command
        this.getCommand("sdversion").setExecutor(new GetVersionCommand(this));
        // Sd items command
        this.getCommand("sditems").setExecutor(new SdItemsCommand(this));
    }

    /**
     * Called when this plugin is disabled.
     */
    @Override
    public void onDisable() {
        // Get the config
        final FileConfiguration config = this.getConfig();
        // Check to see if the jdbc url is present
        final String jdbcUrl = config.getString("jdbcUrl");
        if (jdbcUrl == null) {
            return;
        }
        // Attempt to save item data
        try {
            SqlService.updateProductTable(jdbcUrl, Product.stockPrices);
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Error accessing database", e);
        }
    }

    /**
     * Gets a product from a given item stack.
     *
     * @param itemStack the item stack to check against.
     * @return the found product or null.
     */
    @Nullable
    public Product getProductFromItemStack(@Nonnull final ItemStack itemStack) {
        for (Product product : Product.stockPrices.values()) {
            // if the item stack type and unsafeData equal the products then return it.
            if (product.type.equalsIgnoreCase(itemStack.getType().name()) && product.unsafeData == itemStack.getData().getData()) {
                return product;
            }
        }
        return null;
    }

    /**
     * @return the price map for the products.
     */
    public ConcurrentMap<String, Product> getStockPrices() {
        return Product.stockPrices;
    }

    /**
     * @return the economy service provided by vault.
     */
    public Economy getEconomyService() {
        return economyService;
    }

    /**
     * @return the configuration for this plugin.
     */
    public Configuration getConfiguration() {
        return configuration;
    }
}
