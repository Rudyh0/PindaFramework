package nl.pinda.framework.modules.panel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** De serverconsole in het paneel (meelezen en commando's uitvoeren) en het logboek van het paneel. */
final class ConsoleApi extends PanelApi {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    private static final int TAIL_BYTES = 192 * 1024;
    private static final int MAX_CHUNK = 512 * 1024;

    ConsoleApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/console", PanelUser.CONSOLE, this::read);
        server.post("/api/console", PanelUser.CONSOLE, this::command);
        server.get("/api/log", PanelUser.LOG, this::log);
    }

    private Object read(PanelRequest request) throws IOException {
        Path file = Path.of("logs", "latest.log").toAbsolutePath();
        if (!Files.isRegularFile(file)) {
            return map("offset", 0, "reset", true, "lines", List.of());
        }
        long size = Files.size(file);
        long from = request.queryLong("from", -1);
        boolean reset = from < 0 || from > size || size - from > MAX_CHUNK;
        long start = reset ? Math.max(0, size - TAIL_BYTES) : from;
        byte[] bytes = new byte[(int) (size - start)];
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // doorlezen tot alles binnen is
            }
        }
        int begin = 0;
        if (reset && start > 0) {
            // De eerste regel is waarschijnlijk half; die slaan we over.
            while (begin < bytes.length && bytes[begin] != '\n') {
                begin++;
            }
            begin = Math.min(bytes.length, begin + 1);
        }
        int end = bytes.length;
        while (end > begin && bytes[end - 1] != '\n') {
            end--; // een regel die nog geschreven wordt, komt bij de volgende keer
        }
        List<String> lines = new ArrayList<>();
        if (end > begin) {
            String text = new String(bytes, begin, end - begin, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                lines.add(ANSI.matcher(line.replace("\r", "")).replaceAll(""));
            }
        }
        int max = module.consoleLines();
        if (lines.size() > max) {
            lines = new ArrayList<>(lines.subList(lines.size() - max, lines.size()));
        }
        return map("offset", start + end, "reset", reset, "lines", lines);
    }

    private Object command(PanelRequest request) throws Exception {
        String command = request.string("command", "Typ een commando.");
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isBlank() || command.length() > 1000) {
            throw ApiException.badRequest("Ongeldig commando.");
        }
        String run = command;
        module.log().add(request, "console", null, run);
        boolean known = sync(() -> plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), run));
        return map("known", known);
    }

    private Object log(PanelRequest request) throws Exception {
        int limit = request.queryInt("limit", 100, 1, 500);
        int offset = request.queryInt("offset", 0, 0, 1_000_000);
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (PanelSessions.Session session : module.sessions().active(module.idleMillis(), module.maxMillis())) {
            sessions.add(map("name", session.name, "uuid", session.uuid.toString(), "ip", session.ip,
                    "created", session.created, "lastSeen", session.lastSeen,
                    "current", request.session != null && session.id.equals(request.session.id)));
        }
        return map("entries", await(module.log().recent(limit, offset)), "sessions", sessions);
    }
}
