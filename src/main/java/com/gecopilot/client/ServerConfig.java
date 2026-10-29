package com.gecopilot.client;

public final class ServerConfig {
    private ServerConfig() {}
    public static final String BASE_URL = "https://ge-copilot.onrender.com";
    public static final String DISCORD_URL = "https://discord.gg/zeBkPymf4f";
    public static final String VERSION = "0.1.33-beta";
    // Shown once in the panel after a version update (existing users only). Update per release.
    // HTML fragment (wrapped in <html> by the banner); keep it to a few bullet lines.
    public static final String WHATS_NEW =
        "- Tax-free items such as energy potions, teleport tablets and tools no longer show GE tax in margins and profit.\n"
        + "- Old school bonds bought on the GE now count the 10% fee to make them tradeable again, so their profit and breakeven are no longer overstated.\n"
        + "- Prices and offer totals above 2,147,483,647 gp are now tracked correctly.";
}
