package nl.pinda.framework.modules.backpack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import nl.pinda.framework.PindaFramework;
import org.bukkit.inventory.ItemStack;

/**
 * Bewaart rugtassen in de database. Items worden omgezet naar bytes met het eigen formaat van
 * Paper (dat ook na Minecraft-updates nog goed gelezen wordt).
 */
final class BackpackStore {

    private final PindaFramework plugin;

    BackpackStore(PindaFramework plugin) {
        this.plugin = plugin;
    }

    /** De inhoud van een rugtas (leeg array als er nog niets is). Wordt niet op de hoofdthread afgerond. */
    CompletableFuture<ItemStack[]> load(UUID uuid) {
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT items FROM pinda_backpacks WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? deserialize(result.getBytes(1)) : new ItemStack[0];
                }
            }
        });
    }

    /** Slaat een rugtas op. Zet de items eerst om (aanroepen op de hoofdthread), het schrijven gebeurt op de achtergrond. */
    CompletableFuture<Void> save(UUID uuid, ItemStack[] items) {
        byte[] data = serialize(items);
        return plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_backpacks (uuid, items, updated) VALUES (?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET items = excluded.items, updated = excluded.updated")) {
                statement.setString(1, uuid.toString());
                statement.setBytes(2, data);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        });
    }

    static byte[] serialize(ItemStack[] items) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(items.length);
            for (ItemStack item : items) {
                if (item == null || item.isEmpty()) {
                    out.writeInt(0);
                    continue;
                }
                byte[] data = item.serializeAsBytes();
                out.writeInt(data.length);
                out.write(data);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ItemStack[] deserialize(byte[] data) {
        if (data == null || data.length < 8) {
            return new ItemStack[0];
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            in.readInt(); // versie
            int size = Math.max(0, Math.min(1024, in.readInt()));
            ItemStack[] items = new ItemStack[size];
            for (int slot = 0; slot < size; slot++) {
                int length = in.readInt();
                if (length <= 0) {
                    continue;
                }
                byte[] item = new byte[length];
                in.readFully(item);
                items[slot] = ItemStack.deserializeBytes(item);
            }
            return items;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
