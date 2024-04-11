package com.slackow.packscriptreloader.mixin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.CommonColors;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.function.BooleanSupplier;

import static java.lang.Integer.MAX_VALUE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static net.minecraft.network.chat.ClickEvent.Action.OPEN_URL;
import static net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT;
import static net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR;
import static net.minecraft.world.level.storage.LevelResource.ROOT;

@Mixin(MinecraftServer.class)
public abstract class ServerMixin {
    @Shadow
    private int tickCount;
    @Unique
    private boolean autoUpdate = true;

    @Shadow
    public abstract PackRepository getPackRepository();

    @Shadow
    public abstract Path getWorldPath(LevelResource levelResource);

    @Shadow
    public abstract void sendSystemMessage(Component component);

    @Shadow
    public abstract PlayerList getPlayerList();

    @Shadow
    public abstract Commands getCommands();

    @Shadow
    public abstract CommandSourceStack createCommandSourceStack();

    @Unique
    private Path packscript;

    @Unique
    private Path devDirectory;

    @Unique
    private long lastCopied = 0;

    @Inject(method = "tickServer", at = @At("HEAD"))
    public void tick(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        try {
            if (tickCount == 0) {
                resolvePackscript();
            } else if (tickCount % 30 == 1) {
                checkForCompile();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace(System.err);
        }
    }

    @Unique
    private void checkForCompile() throws IOException {
        attemptFlushBacklog();
        if (!Files.exists(packscript)) return;
        PackRepository pr = getPackRepository();
        long threshold = lastCopied;
        try (var devPacks = Files.list(devDirectory)) {
            devPacks.forEach(devPack -> {
                if (!Files.isDirectory(devPack)) return;
                if (!Files.exists(devPack.resolve("pack.mcmeta"))) return;
                String packName = "file/" + devPack.getFileName();
                Pack datapack = pr.getPack(packName);
                if (datapack != null && !pr.isAvailable(packName)) return;
                if (!Files.isDirectory(devPack.resolve("data"))) return;
                try (var sourceFiles = Files.find(devPack.resolve("data"),
                        MAX_VALUE,
                        (path, basicFileAttributes) -> path.toString().endsWith(".dps") && basicFileAttributes.isRegularFile())) {
                    long modTime = sourceFiles.mapToLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }).max().orElse(0);
//                            alert("For: " + packName + ", modTime: " + modTime + " threshold: " + threshold + " lastCopied: " + lastCopied);
                    if (modTime <= threshold) return;
                    lastCopied = Math.max(lastCopied, modTime);
                    alert("Compiling '" + packName + "'");
                    String[] cmd = {"python3", packscript.toAbsolutePath().toString(), "c",
                            "-i", devPack.toAbsolutePath().toString(),
                            "-o", getWorldPath(DATAPACK_DIR).toAbsolutePath().resolve(devPack.getFileName()).toString()};
                    logAlert(String.join(" ", cmd));
                    Process packscriptProc = Runtime.getRuntime().exec(cmd);
                    if (packscriptProc.waitFor() != 0) {
                        String errors = new String(new BufferedInputStream(packscriptProc.getErrorStream()).readAllBytes());
                        error(errors);
                        return;
                    }
                    getCommands().getDispatcher().execute("reload", createCommandSourceStack());
                    alert("Reloaded!");
                } catch (IOException | InterruptedException | CommandSyntaxException e) {
                    e.printStackTrace(System.err);
                    error(e.getMessage());
                }
            });
        }
    }

    @Unique
    private String pythonLoc = "python3";

    @Unique
    private void resolvePackscript() throws IOException, InterruptedException {
        var configDirectory = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        Files.createDirectories(configDirectory);
        var configFile = configDirectory.resolve("packscript_reloader.txt");
        try {
            Files.write(configFile, List.of("python=python3", "auto-update=true"), CREATE_NEW);
        } catch (IOException ignored) {
        }
        try (var lines = Files.lines(configFile)) {
            lines.forEach(line -> {
                if (line.startsWith("python=")) {
                    pythonLoc = line.substring("python=".length());
                }
                if (line.startsWith("auto-update=")) {
                    autoUpdate = Boolean.parseBoolean(line.substring("auto-update=".length()));
                }
            });
        } catch (IOException e) {
            error("There was an issue reading you config file");
            throw new IOException("Error reading config file", e);
        }

        devDirectory = getWorldPath(ROOT).resolve("dev");
        packscript = devDirectory.resolve("packscript.py");
        Files.createDirectories(devDirectory);
        Process pythonProc = Runtime.getRuntime().exec(new String[]{pythonLoc, "-V"});
        boolean shouldLeave = false;
        if (pythonProc.waitFor() != 0) {
            error("Python3 is not installed/misconfigured in \"" + pythonLoc + "\", please install it/add it to your path, " +
                    "or point your config file to the correct location. Restart the server/world when done.");
            shouldLeave = true;
        } else {
            alert(new String(pythonProc.getInputStream().readNBytes(200)).strip());
        }
        if (!Files.exists(packscript)) {
            packscript = configDirectory.resolve("packscript.py");
        }
        if (!Files.exists(packscript) && !updatePS("a")) {
            error("Packscript not detected, please add it to the root of the dev folder, (under the world) " +
                    "or the config folder (under .minecraft).");
            shouldLeave = true;
        }
        if (shouldLeave) return;
        String[] cmd = {pythonLoc, packscript.toAbsolutePath().toString(), "-V"};
        logAlert(String.join(" ", cmd));
        Process packscriptProc = Runtime.getRuntime().exec(cmd);
        if (packscriptProc.waitFor() != 0) {
            error("Could not get version of packscript");
            error(new String(new BufferedInputStream(packscriptProc.getErrorStream()).readAllBytes()));
            return;
        }
        String version = new String(packscriptProc.getInputStream().readNBytes(200)).strip();
        String packscriptVersion = version.substring(version.lastIndexOf(' ') + 1).strip();
        if (autoUpdate) {
            getLatestVersion().ifPresentOrElse(v -> {
                if (!v.equals(packscriptVersion)) {
                    if (updatePS("b")) {
                        alert("PackScript " + v);
                    }
                } else alert(version);
            }, () -> alert(version));
        } else {
            alert(version);
        }
    }

    @Unique
    private Optional<String> getLatestVersion() {
        if (!autoUpdate) return Optional.empty();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/Slackow/PackScript/releases/latest"))
                .header("Accept", "application/vnd.github.v3+json")  // It's good practice to set the Accept header
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject jsonObject = JsonParser.parseString(response.body()).getAsJsonObject();
            return Optional.of(jsonObject.get("name").getAsString());

        } catch (IOException | InterruptedException e) {
            return Optional.empty();
        }
    }



    @Unique
    private boolean updatePS(String arg) {
        if (!autoUpdate) return false;
        if (!Files.exists(packscript)) {
            packscript = devDirectory.resolve("packscript.py");
        }
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://github.com/Slackow/PackScript/releases/latest/download/packscript.py"))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            Files.writeString(packscript, response.body());
            System.out.println("updated at " + arg);
            alert(Component.literal("Updated PackScript! Changelog: ")
                    .append(Component.literal("https://github.com/Slackow/PackScript/releases/latest")
                            .withStyle(Style.EMPTY.withClickEvent(new ClickEvent(OPEN_URL,
                                    "https://github.com/Slackow/PackScript/releases/latest"))
                                    .withHoverEvent(new HoverEvent(SHOW_TEXT,
                                            Component.literal("Open Changelog"))))));
        } catch (IOException | InterruptedException e) {
            error(e.getMessage());
            return false;
        }
        return Files.exists(packscript);
    }

    @Unique
    private void logAlert(String s) {
        sendSystemMessage(Component.literal(s));
    }

    @Unique
    private void error(String s) {
        _msg(Component.literal("PS> " + s).withColor(CommonColors.RED), true);
    }

    @Unique
    private void alert(String s) {
        alert(Component.literal(s));
    }

    @Unique
    private void alert(Component c) {
        _msg(Component.literal("PS> ").append(c), true);
    }
    @Unique
    private final Queue<Component> backlog = new ArrayDeque<>();
    @Unique
    private void _msg(Component msg, boolean addToBacklog) {
        PlayerList pl = getPlayerList();
        if (!addToBacklog || pl.getPlayers().stream().map(Player::getGameProfile).anyMatch(pl::isOp)) {
            pl.broadcastSystemMessage(msg, false);
        } else if (backlog.size() < 50) {
            backlog.add(msg);
            System.out.println(msg.getString());
        }
    }

    @Unique
    private void attemptFlushBacklog() {
        if (backlog.isEmpty()) return;
        PlayerList pl = getPlayerList();
        if (pl.getPlayers().stream().map(Player::getGameProfile).anyMatch(pl::isOp)) {
            while (!backlog.isEmpty()) {
                _msg(backlog.poll(), false);
            }
        }
    }
}
