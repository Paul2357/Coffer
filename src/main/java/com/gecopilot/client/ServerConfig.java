package com.gecopilot.client;

public final class ServerConfig {
    private ServerConfig() {}
    public static final String BASE_URL = "https://ge-copilot.onrender.com";
    public static final String DISCORD_URL = "https://discord.gg/zeBkPymf4f";
    public static final String VERSION = "0.1.32-beta";
    // Shown once in the panel after a version update (existing users only). Update per release.
    // HTML fragment (wrapped in <html> by the banner); keep it to a few bullet lines.
    public static final String WHATS_NEW =
        "- Sell offers show Profit against your own cost, and \"Profit: unknown\" when Coffer only knows what you paid for part of the stack\n"
        + "- A sell priced below your cost now says \"Sells at a loss\" and notifies you once\n"
        + "- \"Flip edge\" is now \"Market margin\", and full item names wrap onto two lines";
}
