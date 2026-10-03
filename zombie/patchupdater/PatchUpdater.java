package zombie.patchupdater;

import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.input.Mouse;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Keeps the Java patch pack up to date: downloads the pack from Google Drive in the background and installs it when
 * the game exits. A pack holds class files and files under media/ (shaders, textures), all relative to the game folder.
 */
public class PatchUpdater {

    public static final String PACK_NAME = "Trinity's Zomboid Modifications";
    public static final String PACK_ID = "ZomboidModifications";
    private static final String PACK_URL = "https://drive.google.com/uc?export=download&id=1zvOGGUukbOBdw106mwsnC6lepv95fHxn";
    private static final String PACK_INFO = PACK_ID + ".json";
    private static final String CHANGES = "changes.txt";
    private static final UIFont TITLE_FONT = UIFont.Large;
    private static final UIFont BODY_FONT = UIFont.Medium;
    private static final int PADDING = 14, ACCENT_WIDTH = 6, BORDER = 2, MARGIN = 20, GAP = 10, MIN_WIDTH = 380;
    private static final long PULSE_PERIOD_MILLIS = 1200;
    private static final int PULSE_COUNT = 5;
    private static final int CLOSE_SIZE = 22;

    private static volatile Notice statusNotice;
    private static volatile Notice appliedNotice;

    private static boolean started;
    private static boolean installHookRegistered;
    private static boolean warnedNoServerManifest;
    private static volatile boolean updatePending;
    private static Object guardedMenu; // MainScreen.instance the guard was installed on, render thread only

    private static final String MENU_GUARD_LUA = """
            local function guard(option)
                if not option or option.patchUpdaterGuarded then return end
                local original = option.onMouseDown
                option.patchUpdaterGuarded = true
                option.onMouseDown = function(item, x, y)
                    local text = "An update for Trinity's Zomboid Modifications was downloaded,\\\\nbut is only installed when the game restarts.\\\\n\\\\nServers running the update won't let you join until then.\\\\nContinue anyway?"
                    local modal = ISModalDialog:new(0, 0, 420, 160, text, true, nil, function(_, button)
                        if button.internal == "YES" then original(item, x, y) end
                    end)
                    modal:setX(getCore():getScreenWidth() / 2 - modal:getWidth() / 2)
                    modal:setY(getCore():getScreenHeight() / 2 - modal:getHeight() / 2)
                    modal:initialise()
                    modal:addToUIManager()
                    modal:setAlwaysOnTop(true)
                    modal:bringToTop()
                    modal:setCapture(true)
                end
            end
            local menu = MainScreen.instance
            guard(menu.latestSaveOption)
            guard(menu.loadOption)
            guard(menu.onlineOption)
            """;

    private PatchUpdater() {
    }

    private static Path workDir() {
        return Paths.get(ZomboidFileSystem.instance.getCacheDir(), PACK_ID);
    }

    private static Path stagedDir() {
        return workDir().resolve("staged");
    }

    private static Path originalsDir() {
        return workDir().resolve("originals");
    }

    private static Path appliedMarker() {
        return workDir().resolve("applied.txt");
    }

    private static Path gameDir() {
        return Paths.get(System.getProperty("user.dir")); // "." on the classpath - where the patched classes live
    }

    public static void start() {
        if (started) return;
        started = true;

        readAppliedMarker();
        PackInfo installed = readInstalled();
        String running = runningGameVersion();
        if (installed != null && !installed.gameVersion().equals(running)) {
            List<String> body = List.of(String.format("Made for Project Zomboid %s, you're running %s", installed.gameVersion(), running), "Expect problems.");
            statusNotice = new Notice(Notice.Type.WARNING, PACK_NAME + ": Game version mismatch!", body);
        }
        if (Files.isDirectory(stagedDir())) {
            registerInstallOnExit();
            updatePending = true;
            statusNotice = new Notice(Notice.Type.PENDING, PACK_NAME + ": Update ready!", List.of("Restart the game to apply it"));
            return;
        }

        Thread thread = new Thread(PatchUpdater::check, PACK_ID + "-Updater");
        thread.setDaemon(true);
        thread.start();
    }

    private static void check() {
        boolean downloaded = false;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            Map<String, byte[]> entries = readZip(get(client, PACK_URL, HttpResponse.BodyHandlers.ofByteArray()));
            byte[] infoBytes = entries.remove(PACK_INFO);
            if (infoBytes == null) {
                throw new IOException(PACK_INFO + " missing - Drive may have served an error page instead of the pack");
            }
            downloaded = true;
            PackInfo latest = PackInfo.parse(new String(infoBytes, StandardCharsets.UTF_8));

            List<String> changes = describeChanges(readInstalled(), latest);
            if (changes.isEmpty()) return;

            String running = runningGameVersion();
            if (!latest.gameVersion().equals(running)) {
                List<String> body = compareVersions(latest.gameVersion(), running) > 0 ? List.of(String.format("Made for Project Zomboid %s, you're running %s", latest.gameVersion(), running), "Update the game first.") :
                        List.of(String.format("Made for Project Zomboid %s, you're running %s", latest.gameVersion(), running), "Updates for newer game version are not available yet.");
                statusNotice = new Notice(Notice.Type.WARNING, PACK_NAME + ": Game version mismatch!", body);
                return;
            }

            verifyContents(latest, entries);
            stage(entries, infoBytes, changes);
            registerInstallOnExit();
            updatePending = true;
            List<String> body = new ArrayList<>(changes.stream().filter(l -> !l.startsWith("  - ")).toList());
            body.add("Restart the game to apply it.");
            statusNotice = new Notice(Notice.Type.PENDING, PACK_NAME + " update downloaded", body);
        } catch (Exception e) {
            DebugType.General.printException(e, PACK_NAME + " update failed", LogSeverity.Warning);
            statusNotice = downloaded ? new Notice(Notice.Type.WARNING, PACK_NAME + " update failed!", List.of("See console.txt for details.")) : null;
        }
    }

    private static <T> T get(HttpClient client, String url, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<T> response = client.send(request, handler);
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
        return response.body();
    }

    private static PackInfo readInstalled() {
        Path info = gameDir().resolve(PACK_INFO);
        if (!Files.exists(info)) return null;
        try {
            return PackInfo.parse(Files.readString(info));
        } catch (IOException e) {
            DebugType.General.printException(e, PACK_NAME + ": unreadable " + info, LogSeverity.Warning);
            return null;
        }
    }

    private static List<String> describeChanges(PackInfo installed, PackInfo latest) {
        List<String> lines = new ArrayList<>();
        Map<String, Module> previous = installed != null ? installed.modules() : Map.of();
        for (Module module : latest.modules().values()) {
            Module old = previous.get(module.id());
            if (old == null) {
                lines.add(module.name() + " " + module.version() + " (new)");
            } else if (compareVersions(module.version(), old.version()) > 0) {
                lines.add(module.name() + " " + old.version() + " -> " + module.version());
            } else {
                continue;
            }
            module.notes().forEach(note -> lines.add("  - " + note));
        }
        for (Module old : previous.values()) {
            if (!latest.modules().containsKey(old.id())) lines.add(old.name() + " removed");
        }
        return lines;
    }

    private static void verifyContents(PackInfo info, Map<String, byte[]> entries) throws IOException {
        Set<String> owned = info.files();
        for (String name : entries.keySet()) {
            if (!owned.contains(name)) throw new IOException(name + " isn't part of any module");
        }
        for (String file : owned) {
            if (!entries.containsKey(file)) throw new IOException(file + " is listed but missing from the zip");
        }
    }

    private static boolean isPatchFile(String path) {
        if (path.startsWith("/") || path.contains("..") || path.contains(":") || path.contains("//")) return false;
        if (path.startsWith("media/")) return path.length() > "media/".length() && !path.endsWith("/");
        return path.endsWith(".class") && path.indexOf('/') > 0;
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').replace("\uFEFF", "").trim(); // Compress-Archive may write backslashes
    }

    private static String normalizeText(String text) {
        return text.replace("\uFEFF", ""); // JSON written by PowerShell may start with a BOM
    }

    private static List<String> strings(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        }
        return values;
    }

    private static List<String> patchFiles(JSONArray array) throws IOException {
        List<String> files = new ArrayList<>();
        for (String value : strings(array)) {
            String file = normalize(value);
            if (!isPatchFile(file)) throw new IOException("Invalid patch path " + value);
            files.add(file);
        }
        return files;
    }

    private static Map<String, byte[]> readZip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = normalize(entry.getName());
                if (name.endsWith("/")) continue;
                if (!name.equals(PACK_INFO) && !isPatchFile(name))
                    throw new IOException("Unexpected zip entry " + name);
                entries.put(name, in.readAllBytes());
            }
        }
        return entries;
    }

    private static void stage(Map<String, byte[]> classes, byte[] infoBytes, List<String> changes) throws IOException {
        Path tmp = workDir().resolve("staged.tmp");
        deleteRecursively(tmp);
        Path files = tmp.resolve("files");
        Files.createDirectories(files);

        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Path target = files.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        Files.write(tmp.resolve(PACK_INFO), infoBytes);
        Files.write(tmp.resolve(CHANGES), changes);

        deleteRecursively(stagedDir());
        Files.move(tmp, stagedDir(), StandardCopyOption.ATOMIC_MOVE);
    }

    private static synchronized void registerInstallOnExit() {
        if (installHookRegistered) return;
        installHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(PatchUpdater::install, PACK_ID + "-Installer"));
    }

    /**
     * Shutdown hook: removes files the old pack owned (or either pack declares obsolete) that the new pack doesn't
     * contain, copies the new files in and replaces the pack info. Everything it overwrites or deletes is backed up
     * first and restored on failure.
     * <p>
     * A game file the pack overwrites for the first time (e.g. a vanilla shader) is kept in originals/ and put back
     * once no pack contains it anymore, instead of being deleted. Classes don't need this - they fall back to the jar.
     */
    private static void install() {
        Path staged = stagedDir();
        Path stagedFiles = staged.resolve("files");
        if (!Files.isDirectory(stagedFiles)) return;
        Path gameDir = gameDir();
        Path backup = workDir().resolve("backup");
        Path originals = originalsDir();

        List<String> touched;
        Set<String> hadOriginal;
        List<String> newFiles, removed;
        List<String> savedOriginals = new ArrayList<>();

        try {
            PackInfo next = PackInfo.parse(Files.readString(staged.resolve(PACK_INFO)));
            PackInfo previous = readInstalled();
            newFiles = new ArrayList<>(next.files());

            // Removal candidates: everything the old pack owned, plus files either pack declares obsolete
            Set<String> candidates = new LinkedHashSet<>(next.obsolete());
            if (previous != null) {
                candidates.addAll(previous.files());
                candidates.addAll(previous.obsolete());
            }
            candidates.removeAll(next.files());
            removed = new ArrayList<>(candidates);

            touched = new ArrayList<>(newFiles);
            touched.addAll(removed);
            touched.add(PACK_INFO);
            deleteRecursively(backup);
            hadOriginal = backUp(gameDir, backup, touched);

            Set<String> previousFiles = previous != null ? previous.files() : Set.of();
            for (String file : newFiles) {
                Path target = gameDir.resolve(file), original = originals.resolve(file);
                if (previousFiles.contains(file) || !Files.exists(target) || Files.exists(original)) continue;
                Files.createDirectories(original.getParent());
                Files.copy(target, original);
                savedOriginals.add(file);
            }
        } catch (IOException e) {
            System.err.println(PACK_NAME + ": update not installed, nothing was changed: " + e);
            discardOriginals(originals, savedOriginals);
            return;
        }

        try {
            for (String file : removed) {
                Path target = gameDir.resolve(file), original = originals.resolve(file);
                if (Files.exists(original)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(original, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(target);
                    deleteEmptyParents(gameDir, target.getParent());
                }
            }
            for (String file : newFiles) {
                Path target = gameDir.resolve(file);
                Files.createDirectories(target.getParent());
                Files.copy(stagedFiles.resolve(file), target, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(staged.resolve(PACK_INFO), gameDir.resolve(PACK_INFO), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println(PACK_NAME + ": installing the update failed, rolling back: " + e);
            restore(gameDir, backup, touched, hadOriginal);
            discardOriginals(originals, savedOriginals);
            return;
        }

        discardOriginals(originals, removed); // restored above
        try {
            Files.move(staged.resolve(CHANGES), appliedMarker(), StandardCopyOption.REPLACE_EXISTING);
            deleteRecursively(staged);
            deleteRecursively(backup);
        } catch (IOException e) {
            System.err.println(PACK_NAME + ": cleanup after installing failed: " + e);
        }
    }

    private static Set<String> backUp(Path gameDir, Path backup, List<String> files) throws IOException {
        Set<String> existed = new HashSet<>();
        for (String file : files) {
            Path target = gameDir.resolve(file);
            if (!Files.exists(target)) continue;
            Path saved = backup.resolve(file);
            Files.createDirectories(saved.getParent());
            Files.copy(target, saved, StandardCopyOption.REPLACE_EXISTING);
            existed.add(file);
        }
        return existed;
    }

    private static void restore(Path gameDir, Path backup, List<String> files, Set<String> hadOriginal) {
        for (String file : files) {
            try {
                Path target = gameDir.resolve(file);
                if (hadOriginal.contains(file)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(backup.resolve(file), target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(target);
                }
            } catch (IOException e) {
                System.err.println(PACK_NAME + ": rollback failed for " + file + ": " + e);
            }
        }
    }

    private static void discardOriginals(Path originals, List<String> files) {
        for (String file : files) {
            try {
                Path original = originals.resolve(file);
                if (Files.deleteIfExists(original)) deleteEmptyParents(originals, original.getParent());
            } catch (IOException e) {
                System.err.println(PACK_NAME + ": couldn't remove the saved original of " + file + ": " + e);
            }
        }
    }

    private static void deleteEmptyParents(Path gameDir, Path dir) throws IOException {
        while (dir != null && !dir.equals(gameDir) && dir.startsWith(gameDir) && Files.isDirectory(dir)) {
            try (Stream<Path> entries = Files.list(dir)) {
                if (entries.findAny().isPresent()) return;
            }
            Files.delete(dir);
            dir = dir.getParent();
        }
    }

    private static void readAppliedMarker() {
        Path marker = appliedMarker();
        if (!Files.exists(marker)) return;
        try {
            appliedNotice = new Notice(Notice.Type.SUCCESS, PACK_NAME + " updated!", Files.readAllLines(marker));
            Files.delete(marker);
        } catch (IOException e) {
            DebugType.General.printException(e, PACK_NAME + ": unreadable " + marker, LogSeverity.Warning);
        }
    }

    public static void renderMainMenuNotice() {
        guardMenu();
        int y = MARGIN;
        Notice applied = appliedNotice;
        if (applied != null && !applied.isDismissed()) y = renderNotice(applied, y) + GAP;
        Notice status = statusNotice;
        if (status != null && !status.isDismissed()) {
            renderNotice(status, y);
        }
    }

    private static int renderNotice(Notice notice, int y) {
        TextManager text = TextManager.instance;
        int titleHeight = text.getFontHeight(TITLE_FONT);
        int lineHeight = text.getFontHeight(BODY_FONT);
        int contentWidth = text.MeasureStringX(TITLE_FONT, notice.title()) + GAP + CLOSE_SIZE;
        for (String line : notice.body()) {
            contentWidth = Math.max(contentWidth, text.MeasureStringX(BODY_FONT, line));
        }

        int width = Math.max(MIN_WIDTH, ACCENT_WIDTH + PADDING * 2 + contentWidth);
        int height = PADDING * 2 + titleHeight + (notice.body().isEmpty() ? 0 : 6 + notice.body().size() * lineHeight);
        int x = Core.getInstance().getScreenWidth() - width - MARGIN;
        Notice.Type type = notice.type();
        float pulse = pulse(notice); // 1 = resting brightness
        float borderAlpha = 0.3f + 0.5f * pulse;
        float accentAlpha = 0.5f + 0.5f * pulse;

        SpriteRenderer sprites = SpriteRenderer.instance;
        sprites.renderi(null, x, y, width, height, 0.06f, 0.06f, 0.07f, 0.9f, null); // background
        sprites.renderi(null, x, y, ACCENT_WIDTH, height, type.r, type.g, type.b, accentAlpha, null); // accent bar
        sprites.renderi(null, x, y, width, BORDER, type.r, type.g, type.b, borderAlpha, null); // top
        sprites.renderi(null, x, y + height - BORDER, width, BORDER, type.r, type.g, type.b, borderAlpha, null); // bottom
        sprites.renderi(null, x + width - BORDER, y, BORDER, height, type.r, type.g, type.b, borderAlpha, null); // right

        int textX = x + ACCENT_WIDTH + PADDING, textY = y + PADDING;
        text.DrawString(TITLE_FONT, textX, textY, notice.title(), type.r, type.g, type.b, 1.0f);
        textY += titleHeight + 6;
        for (String line : notice.body()) {
            boolean note = line.startsWith("  - ");
            double shade = note ? 0.72 : 1.0; // notes in gray, under their change line
            text.DrawString(BODY_FONT, textX + (note ? 12 : 0), textY, note ? line.substring(2) : line, shade, shade, shade, 1.0);
            textY += lineHeight;
        }

        int closeX = x + width - BORDER - PADDING / 2 - CLOSE_SIZE;
        int closeY = y + PADDING + (titleHeight - CLOSE_SIZE) / 2;
        int mouseX = Mouse.getXA(), mouseY = Mouse.getYA();
        boolean hovered = mouseX >= closeX && mouseX < closeX + CLOSE_SIZE && mouseY >= closeY && mouseY < closeY + CLOSE_SIZE;
        if (hovered) {
            sprites.renderi(null, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE, 1.0f, 1.0f, 1.0f, 0.12f, null);
            if (Mouse.isLeftPressed()) notice.dismiss();
        }
        float closeShade = hovered ? 1.0f : 0.55f;
        int glyphX = closeX + (CLOSE_SIZE - text.MeasureStringX(BODY_FONT, "X")) / 2;
        int glyphY = closeY + (CLOSE_SIZE - lineHeight) / 2;
        text.DrawString(BODY_FONT, glyphX, glyphY, "X", closeShade, closeShade, closeShade, 1.0);

        return y + height;
    }

    private static float pulse(Notice notice) {
        long now = System.currentTimeMillis();
        if (notice.firstShownMillis < 0) notice.firstShownMillis = now;
        long age = now - notice.firstShownMillis;
        if (notice.type() == Notice.Type.SUCCESS && age >= PULSE_PERIOD_MILLIS * PULSE_COUNT) return 1.0f;
        return (float) (0.5 + 0.5 * Math.cos(2.0 * Math.PI * age / PULSE_PERIOD_MILLIS));
    }

    static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\."), partsB = b.split("\\.");
        for (int i = 0; i < Math.max(partsA.length, partsB.length); i++) {
            int valueA = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            int valueB = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            if (valueA != valueB) return Integer.compare(valueA, valueB);
        }
        return 0;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String runningGameVersion() {
        String version = Core.getInstance().getVersion();
        int space = version.indexOf(' ');
        return space < 0 ? version : version.substring(0, space);
    }

    public static String installedFingerprint() {
        PackInfo installed = readInstalled();
        if (installed == null) return "";
        return installed.modules.values().stream()
                .map(module -> module.id() + "=" + module.version())
                .sorted()
                .collect(Collectors.joining(","));
    }

    @Nullable
    public static String checkClientPack(@Nullable String clientFingerprint) {
        PackInfo server = readInstalled();
        if (server == null) {
            if (!warnedNoServerManifest) {
                warnedNoServerManifest = true;
                DebugType.General.warn(PACK_NAME + ": no " + PACK_INFO + " next to the server, skipping the client version check");
            }
            return null;
        }
        if (clientFingerprint == null) return "ModRequired##" + PACK_NAME + " (latest version)";

        Map<String, String> client = new HashMap<>();
        for (String pair : clientFingerprint.split(",")) {
            int split = pair.indexOf('=');
            if (split > 0) client.put(pair.substring(0, split), pair.substring(split + 1));
        }
        List<String> clientSide = new ArrayList<>(), serverSide = new ArrayList<>();
        for (Module module : server.modules().values()) {
            String clientVersion = client.remove(module.id());
            if (!module.version().equals(clientVersion)) {
                clientSide.add(module.name() + " " + (clientVersion == null ? "missing" : clientVersion));
                serverSide.add(module.name() + " " + module.version());
            }
        }
        client.forEach((id, version) -> {
            clientSide.add(id + " " + version);
            serverSide.add(id + " missing");
        });
        if (clientSide.isEmpty()) return null;
        return "ClientVersionMismatch##" + String.join(", ", clientSide) + "##" + String.join(", ", serverSide);
    }

    private static void guardMenu() {
        if (!updatePending || !(LuaManager.env.rawget("MainScreen") instanceof KahluaTable mainScreen)) return;
        Object menu = mainScreen.rawget("instance");
        if (menu == null || menu == guardedMenu) return;
        guardedMenu = menu;
        try {
            LuaClosure guard = LuaCompiler.loadstring(MENU_GUARD_LUA, PACK_ID + "-MenuGuard", LuaManager.env);
            LuaManager.caller.pcallvoid(LuaManager.thread, guard, null);
        } catch (Exception e) {
            DebugType.General.printException(e, PACK_NAME + ": couldn't guard the main menu", LogSeverity.Warning);
        }
    }

    public record Module(String id, String name, String version, List<String> notes, List<String> files) {}

    public record PackInfo(String gameVersion, Map<String, Module> modules, List<String> obsolete) {

        static PackInfo parse(String json) throws IOException {
            try {
                JSONObject root = new JSONObject(normalizeText(json));
                Map<String, Module> modules = new LinkedHashMap<>();
                Set<String> owned = new HashSet<>();
                JSONArray array = root.getJSONArray("modules");
                for (int i = 0; i < array.length(); i++) {
                    JSONObject m = array.getJSONObject(i);
                    List<String> files = patchFiles(m.optJSONArray("files"));
                    for (String file : files) {
                        if (!owned.add(file)) throw new IOException(file + " belongs to more than one module");
                    }
                    Module module = new Module(m.getString("id"), m.getString("name"), m.getString("version"), strings(m.optJSONArray("notes")), files);
                    if (modules.put(module.id(), module) != null)
                        throw new IOException("Duplicate module id " + module.id());
                }
                return new PackInfo(root.getString("gameVersion"), modules, patchFiles(root.optJSONArray("obsolete")));
            } catch (JSONException e) {
                throw new IOException("Malformed " + PACK_INFO, e);
            }
        }

        Set<String> files() {
            Set<String> files = new LinkedHashSet<>();
            this.modules.values().forEach(module -> files.addAll(module.files()));
            return files;
        }
    }

    private static final class Notice {
        private final Type type;
        private final String title;
        private final List<String> body;
        private long firstShownMillis = -1; // render thread only
        private boolean dismissed;

        private Notice(Type type, String title, List<String> body) {
            this.type = type;
            this.title = title;
            this.body = body;
        }

        Type type() {
            return this.type;
        }

        String title() {
            return this.title;
        }

        List<String> body() {
            return this.body;
        }

        public boolean isDismissed() {
            return this.dismissed;
        }

        public void dismiss() {
            this.dismissed = true;
        }

        private enum Type {
            SUCCESS(0.36f, 0.72f, 0.44f),
            PENDING(1.0f, 0.75f, 0.2f),
            WARNING(1.0f, 0.4f, 0.3f);

            final float r, g, b;

            Type(float r, float g, float b) {
                this.r = r;
                this.g = g;
                this.b = b;
            }
        }

    }

}
