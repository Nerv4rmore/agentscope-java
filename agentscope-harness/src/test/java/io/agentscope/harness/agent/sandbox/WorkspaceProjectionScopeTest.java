package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Projection must only carry the calling scope's own {@code .skills-cache} subtree, and narrowing
 * it must not leak into the build-time spec that every other call shares.
 */
@DisplayName("WorkspaceProjectionApplier: per-scope .skills-cache narrowing")
class WorkspaceProjectionScopeTest {

    private static void writeFile(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static WorkspaceSpec specWithProjection(Path sourceRoot) {
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(sourceRoot.toString());
        projection.setIncludeRoots(List.of("AGENTS.md", "skills", MarketplaceStager.CACHE_DIR));
        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>();
        entries.put(WorkspaceProjectionEntry.ENTRY_KEY, projection);
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setEntries(entries);
        return spec;
    }

    /** Entry names of the built tar — the payload is a plain tar, not compressed. */
    private static List<String> tarEntries(WorkspaceProjectionApplier.ProjectionPayload payload)
            throws Exception {
        List<String> names = new ArrayList<>();
        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(payload.tarBytes()))) {
            for (ArchiveEntry e = tar.getNextEntry(); e != null; e = tar.getNextEntry()) {
                names.add(e.getName());
            }
        }
        return names;
    }

    @Test
    @DisplayName("narrowing drops other scopes' staged files but keeps the caller's own")
    void narrowExcludesOtherScopes(@TempDir Path ws) throws Exception {
        writeFile(ws.resolve("AGENTS.md"), "root");
        writeFile(ws.resolve("skills/alpha/SKILL.md"), "alpha");
        writeFile(ws.resolve(MarketplaceStager.CACHE_DIR + "/alice/ns/s1/run.sh"), "alice");
        writeFile(ws.resolve(MarketplaceStager.CACHE_DIR + "/bob/ns/s1/run.sh"), "bob");

        WorkspaceSpec perCall = specWithProjection(ws);
        String segment =
                MarketplaceStager.cacheSegmentFor(
                        IsolationScope.USER, RuntimeContext.builder().userId("alice").build());
        WorkspaceProjectionApplier.narrowIncludeRoot(
                perCall, MarketplaceStager.CACHE_DIR, MarketplaceStager.CACHE_DIR + "/" + segment);

        List<String> entries = tarEntries(WorkspaceProjectionApplier.build(perCall));
        assertTrue(entries.contains("skills/alpha/SKILL.md"), "skills root still projected");
        assertTrue(
                entries.contains(MarketplaceStager.CACHE_DIR + "/alice/ns/s1/run.sh"),
                "caller's own subtree must stay, got " + entries);
        assertFalse(
                entries.stream().anyMatch(n -> n.contains("/bob/")),
                "another user's staged skills must not be uploaded, got " + entries);
    }

    @Test
    @DisplayName("narrowing one call leaves the shared build-time spec untouched")
    void narrowingDoesNotMutateSharedEntry(@TempDir Path ws) throws Exception {
        writeFile(ws.resolve(MarketplaceStager.CACHE_DIR + "/alice/ns/s1/run.sh"), "a");
        writeFile(ws.resolve(MarketplaceStager.CACHE_DIR + "/bob/ns/s1/run.sh"), "b");

        WorkspaceSpec buildTime = specWithProjection(ws);
        // WorkspaceSpec.copy() shares entry instances by design — reproduce what SandboxManager
        // hands to the narrow call.
        WorkspaceSpec perCall = buildTime.copy();
        WorkspaceProjectionApplier.narrowIncludeRoot(
                perCall, MarketplaceStager.CACHE_DIR, MarketplaceStager.CACHE_DIR + "/bob");

        assertEquals(
                List.of("AGENTS.md", "skills", MarketplaceStager.CACHE_DIR),
                ((WorkspaceProjectionEntry)
                                buildTime.getEntries().get(WorkspaceProjectionEntry.ENTRY_KEY))
                        .getIncludeRoots(),
                "the build-time spec must not be narrowed for every other caller");

        List<String> entries = tarEntries(WorkspaceProjectionApplier.build(perCall));
        assertTrue(entries.contains(MarketplaceStager.CACHE_DIR + "/bob/ns/s1/run.sh"));
        assertFalse(entries.contains(MarketplaceStager.CACHE_DIR + "/alice/ns/s1/run.sh"));
    }

    @Test
    @DisplayName("scopes with no per-call identity key on the bucket the stager writes to")
    void sharedBucketMatchesStagerDefault() {
        // The stager collapses a null identity to _shared; the projection must resolve the same
        // segment or it would hydrate a subtree that nothing ever writes into.
        assertEquals(
                MarketplaceStager.SHARED_SCOPE,
                MarketplaceStager.cacheSegmentFor(IsolationScope.AGENT, null));
        assertEquals(
                MarketplaceStager.SHARED_SCOPE,
                MarketplaceStager.cacheSegmentFor(IsolationScope.GLOBAL, null));
        assertEquals(
                MarketplaceStager.SHARED_SCOPE,
                MarketplaceStager.cacheSegmentFor(IsolationScope.USER, RuntimeContext.empty()));
    }
}
