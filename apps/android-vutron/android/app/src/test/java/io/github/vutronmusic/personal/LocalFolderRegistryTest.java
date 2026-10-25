package io.github.vutronmusic.personal;

import org.json.JSONArray;
import org.junit.Test;

import static org.junit.Assert.*;

public class LocalFolderRegistryTest {
    @Test public void migratesTheExistingTreeAndKeepsMultipleSelectionsWithoutDuplicates() throws Exception {
        JSONArray first = LocalFolderStore.mergeTreeUris(new JSONArray(), "content://music/tree/one");
        JSONArray two = LocalFolderStore.mergeTreeUris(first, "content://music/tree/two");
        JSONArray selectedAgain = LocalFolderStore.mergeTreeUris(two, "content://music/tree/one");
        assertEquals(2, selectedAgain.length());
        assertEquals("content://music/tree/one", selectedAgain.getString(0));
        assertEquals("content://music/tree/two", selectedAgain.getString(1));
        assertEquals(2, LocalFolderStore.mergeTreeUris(selectedAgain, "").length());
    }

    @Test public void folderIdsAreStableAndDoNotCollideForDifferentTrees() {
        assertEquals(LocalFolderStore.folderId("content://music/tree/one"), LocalFolderStore.folderId("content://music/tree/one"));
        assertNotEquals(LocalFolderStore.folderId("content://music/tree/one"), LocalFolderStore.folderId("content://music/tree/two"));
        assertTrue(LocalFolderStore.folderId("content://music/tree/one").startsWith("folder:"));
    }
}
