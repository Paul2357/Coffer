package com.gecopilot.client;

import org.junit.Test;

import static org.junit.Assert.*;

/** The server's notice text can't inject chat tags: ChatMessageBuilder.append(String) escapes it. */
public class ChatNoticeTest {
    @Test public void angleBracketsAreEscaped() {
        String line = GeCopilotCloudPlugin.chatLine("<col=ff0000>Free gp</col> <img=1>");
        assertFalse(line, line.contains("<col=ff0000>Free"));
        assertFalse(line, line.contains("<img=1>"));
        assertTrue(line, line.contains("<lt>col=ff0000<gt>Free gp"));
    }
}
