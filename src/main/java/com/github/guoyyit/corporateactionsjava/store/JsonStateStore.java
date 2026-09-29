package com.github.guoyyit.corporateactionsjava.store;

import com.github.guoyyit.corporateactionsjava.domain.CorporateAction;
import com.github.guoyyit.corporateactionsjava.domain.RawPrice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Component
public class JsonStateStore {

    public record PersistedState(
            List<CorporateAction> actions,
            List<RawPrice> prices
    ) {
    }

    private final Path dataFile;
    private final ObjectMapper objectMapper;

    public JsonStateStore(@Value("${corporate-actions.data-file:data/corporate-actions.json}") String dataFile) {
        this.dataFile = Path.of(dataFile);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    public PersistedState load() {
        if (!Files.exists(dataFile)) {
            return new PersistedState(List.of(), List.of());
        }
        try {
            PersistedState state = objectMapper.readValue(dataFile.toFile(), PersistedState.class);
            List<CorporateAction> actions = state.actions() == null ? List.of() : state.actions();
            List<RawPrice> prices = state.prices() == null ? List.of() : state.prices();
            return new PersistedState(
                    List.copyOf(actions),
                    List.copyOf(prices));
        } catch (IOException ex) {
            throw new UncheckedIOException("无法读取持久化文件 " + dataFile, ex);
        }
    }

    public synchronized void save(PersistedState state) {
        try {
            Path parent = dataFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, "state-", ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
                try {
                    Files.move(temp, dataFile,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("无法写入持久化文件 " + dataFile, ex);
        }
    }
}
