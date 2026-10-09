package pl.example.cvchecker.storage;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class TempFileStorageTest {

    @TempDir
    Path root;

    @Test
    void storeOriginalWritesBytesUnderRoot() throws Exception {
        TempFileStorage storage = new TempFileStorage(root);
        byte[] data = "%PDF-1.4 test".getBytes();

        Path stored = storage.storeOriginal("abc-123", data);

        assertThat(stored).isEqualTo(root.resolve("abc-123.pdf"));
        assertThat(Files.readAllBytes(stored)).containsExactly(data);
    }
}
