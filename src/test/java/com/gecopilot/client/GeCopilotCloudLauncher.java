package com.gecopilot.client;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GeCopilotCloudLauncher {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(GeCopilotCloudPlugin.class);
        RuneLite.main(args);
    }
}
