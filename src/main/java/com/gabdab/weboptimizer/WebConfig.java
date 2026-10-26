package com.gabdab.weboptimizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class WebConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("weboptimizer.json");

    public static WebConfig INSTANCE = new WebConfig();

    public boolean enabled = false;
    /** 0..1000, 0 = instant, 1000 = maximum smoothness */
    public int smoothness = 100;
    public boolean returnSword = true;
    public int hudX = 20;
    public int hudY = 20;

    public static void load() {
        if (!Files.exists(FILE)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(FILE)) {
            WebConfig loaded = GSON.fromJson(reader, WebConfig.class);
            if (loaded != null) {
                INSTANCE = loaded;
                INSTANCE.smoothness = Math.max(0, Math.min(1000, INSTANCE.smoothness));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
