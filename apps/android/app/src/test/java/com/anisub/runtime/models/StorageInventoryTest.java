package com.anisub.runtime.models;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class StorageInventoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void cacheCleanupNeverTouchesSiblingModelFiles() throws Exception {
        File cache=temp.newFolder("cache"), models=temp.newFolder("voices");
        Files.write(new File(models,"model.onnx").toPath(),new byte[5]);
        File nested=new File(cache,"nested");assertTrue(nested.mkdir());
        Files.write(new File(nested,"cached").toPath(),new byte[9]);
        assertEquals(9,StorageInventory.bytes(cache));
        StorageInventory.clearCache(cache);
        assertTrue(cache.isDirectory());assertEquals(0,cache.list().length);
        assertEquals(5,StorageInventory.bytes(models));
    }
    @Test public void absentCacheIsNotCreatedOrUsedAsModelStorage() throws Exception {
        File missing=new File(temp.getRoot(),"missing");
        assertEquals(0,StorageInventory.bytes(missing));StorageInventory.clearCache(missing);assertFalse(missing.exists());
    }
}
