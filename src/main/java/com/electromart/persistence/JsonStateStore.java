package com.electromart.persistence;

import com.electromart.domain.IdempotencyRecord;
import com.electromart.domain.InventoryAdjustmentRecord;
import com.electromart.domain.Order;
import com.electromart.domain.OrderLine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes the single JSON state file.
 *
 * <p>Writing is done through a temporary file in the same directory followed by an atomic
 * rename, so an interrupted write can never leave a half written state file behind.</p>
 */
public class JsonStateStore {

    private final Path file;
    private final ObjectMapper mapper;

    public JsonStateStore(Path file) {
        this.file = file.toAbsolutePath();
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /**
     * Reads the state file.
     *
     * @return the stored state, or {@link Optional#empty()} when the file does not exist yet
     * @throws StateFileException when the file exists but cannot be read or is not valid
     */
    public Optional<PersistentState> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) {
            throw new StateFileException("State file '" + file + "' is not a regular file.");
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StateFileException("Unable to read state file '" + file + "': " + e.getMessage(), e);
        }
        PersistentState state;
        try {
            state = mapper.readValue(json, PersistentState.class);
        } catch (JsonProcessingException e) {
            throw new StateFileException(
                    "State file '" + file + "' is not valid ElectroMart state JSON: " + e.getOriginalMessage()
                            + " Fix or remove the file (a backup is recommended); the server will not overwrite it.",
                    e);
        }
        if (state == null) {
            throw new StateFileException("State file '" + file + "' is empty or contains 'null'.");
        }
        validate(state);
        return Optional.of(state);
    }

    /** Fails fast on structurally parseable but semantically broken state files. */
    private void validate(PersistentState state) {
        if (state.version() <= 0) {
            throw new StateFileException("State file '" + file + "' has an invalid version: " + state.version() + ".");
        }
        if (state.version() > PersistentState.CURRENT_VERSION) {
            throw new StateFileException("State file '" + file + "' was written by a newer version ("
                    + state.version() + " > " + PersistentState.CURRENT_VERSION + ").");
        }
        for (Map.Entry<String, Integer> entry : state.inventory().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new StateFileException("State file '" + file + "' contains an inventory entry without product id.");
            }
            if (entry.getValue() == null || entry.getValue() < 0) {
                throw new StateFileException("State file '" + file + "' contains a negative stock for product '"
                        + entry.getKey() + "'.");
            }
        }
        for (Order order : state.orders()) {
            if (order == null || order.id() == null || order.id().isBlank()) {
                throw new StateFileException("State file '" + file + "' contains an order without id.");
            }
            if (order.user() == null || order.user().isBlank()) {
                throw new StateFileException("Order '" + order.id() + "' in state file '" + file + "' has no user.");
            }
            if (order.createdAt() == null || order.createdAt().isBlank()) {
                throw new StateFileException("Order '" + order.id() + "' in state file '" + file
                        + "' has no creation timestamp.");
            }
            if (order.items() == null || order.items().isEmpty()) {
                throw new StateFileException("Order '" + order.id() + "' in state file '" + file + "' has no items.");
            }
            for (OrderLine line : order.items()) {
                if (line == null || line.productId() == null || line.productId().isBlank()) {
                    throw new StateFileException("Order '" + order.id() + "' in state file '" + file
                            + "' contains a line without product id.");
                }
                if (line.quantity() <= 0) {
                    throw new StateFileException("Order '" + order.id() + "' in state file '" + file
                            + "' contains a line with a non positive quantity.");
                }
            }
        }
        for (Map.Entry<String, IdempotencyRecord> entry : state.idempotency().entrySet()) {
            IdempotencyRecord record = entry.getValue();
            if (record == null || record.orderId() == null || record.requestHash() == null) {
                throw new StateFileException("State file '" + file + "' contains an incomplete idempotency record for key '"
                        + entry.getKey() + "'.");
            }
        }
        if (state.adjustments() != null) {
            for (Map.Entry<String, InventoryAdjustmentRecord> entry : state.adjustments().entrySet()) {
                InventoryAdjustmentRecord record = entry.getValue();
                if (record == null || record.key() == null || record.key().isBlank()
                        || record.productId() == null || record.productId().isBlank()
                        || record.productName() == null || record.productName().isBlank()
                        || record.quantityAdded() <= 0 || record.resultingStock() < 0) {
                    throw new StateFileException("State file '" + file + "' contains an incomplete adjustment record for key '"
                            + entry.getKey() + "'.");
                }
            }
        }
    }

    /**
     * Writes the state atomically: serialize, write to a temporary file in the same directory,
     * flush it to disk and then atomically replace the real file.
     */
    public void write(PersistentState state) {
        String json;
        try {
            json = mapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new StateFileException("Unable to serialize state: " + e.getOriginalMessage(), e);
        }

        Path directory = file.getParent();
        Path temp = null;
        try {
            if (directory != null) {
                Files.createDirectories(directory);
            }
            temp = Files.createTempFile(directory, ".electromart-state-", ".tmp");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            move(temp, file);
            temp = null;
        } catch (IOException e) {
            throw new StateFileException("Unable to write state file '" + file + "': " + e.getMessage(), e);
        } finally {
            deleteQuietly(temp);
        }
    }

    private void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort clean up of the temporary file
        }
    }

    /** Convenience helper used by tests. */
    public String readRaw() {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
