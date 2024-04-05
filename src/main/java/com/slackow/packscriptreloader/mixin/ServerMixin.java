package com.slackow.packscriptreloader.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.CommonColors;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static java.lang.Integer.MAX_VALUE;
import static net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR;
import static net.minecraft.world.level.storage.LevelResource.ROOT;

@Mixin(MinecraftServer.class)
public abstract class ServerMixin {
    @Shadow
    private int tickCount;

    @Shadow
    public abstract PackRepository getPackRepository();

    @Shadow
    public abstract Path getWorldPath(LevelResource levelResource);

    @Shadow
    public abstract void sendSystemMessage(Component component);

    @Shadow public abstract PlayerList getPlayerList();

    @Shadow public abstract Commands getCommands();

    @Shadow public abstract CommandSourceStack createCommandSourceStack();

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
                devDirectory = getWorldPath(ROOT).resolve("dev");
                packscript = devDirectory.resolve("packscript.py");
                Files.createDirectories(devDirectory);
                if (Runtime.getRuntime().exec("python3 -V").waitFor() != 0) {
                    error("Python3 is not installed, please install it/add it to your path.");
                }
                if (!Files.exists(packscript)) {
                    error("Packscript not detected, please add it to the root of the dev folder.");
                }
            }
            if (tickCount % 30 == 0) {
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
                            alert("Compiling " + packName);
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
                        } catch (IOException | InterruptedException | CommandSyntaxException e) {
                            e.printStackTrace(System.err);
                            error(e.getMessage());
                        }
                    });
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace(System.err);
        }
    }

    @Unique
    private void logAlert(String s) {
        sendSystemMessage(Component.literal(s));
    }

    @Unique
    private void error(String s) {
        getPlayerList().broadcastSystemMessage(Component.literal("PS> " + s).withColor(CommonColors.RED), false);
    }

    @Unique
    private void alert(String s) {
        getPlayerList().broadcastSystemMessage(Component.literal("PS> " + s), false);
    }
}