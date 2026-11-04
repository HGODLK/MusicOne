package com.musicone.build;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ProfileNamespaceCompatibilityTaskTest {
    @Test public void remapsOnlyExistingExactDescriptorsAndUnionsFlags() {
        var classes = Map.of("com/musicone/Lyrics", Set.of("show(Lcom/musicone/Track;)V"),
            "com/musicone/Lyrics$$ExternalSyntheticLambda2", Set.of("run()V"));
        var result = ProfileNamespaceCompatibilityTask.remap(List.of(
            "HLcom/musicone/demo/Lyrics;->show(Lcom/musicone/demo/Track;)V",
            "SPLcom/musicone/demo/Lyrics;->show(Lcom/musicone/demo/Track;)V",
            "Lcom/musicone/demo/Lyrics;",
            "HLcom/musicone/demo/Lyrics;->show(I)V",
            "HLcom/musicone/demo/Lyrics$$ExternalSyntheticLambda1;->run()V",
            "HLcom/musicone/demo/Lyrics$$ExternalSyntheticLambda2;->missing()V",
            "HLandroidx/compose/Unchanged;->run()V"), classes);
        assertEquals(2, result.size());
        assertEquals("HSP", result.get("Lcom/musicone/Lyrics;->show(Lcom/musicone/Track;)V"));
        assertEquals("", result.get("Lcom/musicone/Lyrics;"));
    }

    @Test public void readsActualClassNamesAndMethodDescriptors() throws Exception {
        Map<String, Set<String>> classes = new HashMap<>();
        try (var stream = getClass().getResourceAsStream("ProfileNamespaceCompatibilityTaskTest.class")) {
            ProfileNamespaceCompatibilityTask.readClass(Objects.requireNonNull(stream), classes);
        }
        assertTrue(classes.get("com/musicone/build/ProfileNamespaceCompatibilityTaskTest")
            .contains("readsActualClassNamesAndMethodDescriptors()V"));
    }
}
