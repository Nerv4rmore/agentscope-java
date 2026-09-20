package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A filesystem repository rooted at the workspace's own {@code skills/} directory must not be
 * re-staged: projection already ships that tree, so staging duplicates the whole skill library
 * once per isolation scope and every non-caller copy is never collected.
 */
@DisplayName("MarketplaceStager: workspace-rooted filesystem repos are not duplicated")
class MarketplaceStagerWorkspaceNativeTest {

    private static AgentSkill writeSkill(Path skillDir, String frontmatterName, String body)
            throws IOException {
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: "
                        + frontmatterName
                        + "\ndescription: "
                        + frontmatterName
                        + " skill\n---\n"
                        + body
                        + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                skillDir.resolve("run.sh"), "#!/bin/sh\necho hi\n", StandardCharsets.UTF_8);
        return new FileSystemSkillRepository(skillDir.getParent(), false).getAllSkills().get(0);
    }

    @Test
    @DisplayName(
            "skills under <ws>/skills are projected, not staged — and the dir name wins over the"
                    + " skill name")
    void workspaceSkillsAreNotStaged(@TempDir Path ws) throws Exception {
        // Mirrors the production shape: directory `video-generation`, frontmatter name
        // `video-generator`. The rendered path must be the real directory.
        AgentSkill skill =
                writeSkill(
                        ws.resolve("skills").resolve("video-generation"),
                        "video-generator",
                        "body");
        FileSystemSkillRepository repo = new FileSystemSkillRepository(ws.resolve("skills"), false);
        MarketplaceStager stager = new MarketplaceStager(ws);

        Map<String, StageResult> staged =
                stager.stage(
                        List.of(new RepoBound(skill, repo)), new IdentityHashMap<>(), "user-1");

        assertEquals(
                new StageResult.WorkspaceNative("skills/video-generation"),
                staged.get("video-generator"));
        assertFalse(
                Files.exists(ws.resolve(MarketplaceStager.CACHE_DIR)),
                "nothing should be materialised under .skills-cache");

        assertEquals(
                "/workspace/skills/video-generation",
                ShellPathPolicy.sandbox().resolve("video-generator", staged.get("video-generator")),
                "filesRoot must point at the projected directory, not skills/<name>");
    }

    @Test
    @DisplayName("a repo rooted outside the workspace still stages as before")
    void externalRepoIsStillStaged(@TempDir Path ws) throws Exception {
        Path external = ws.getParent().resolve("outside-skills").resolve("db-backed");
        AgentSkill skill = writeSkill(external, "db-backed", "body");
        FileSystemSkillRepository repo = new FileSystemSkillRepository(external.getParent(), false);
        MarketplaceStager stager = new MarketplaceStager(ws);

        Map<String, StageResult> staged =
                stager.stage(
                        List.of(new RepoBound(skill, repo)), new IdentityHashMap<>(), "user-1");

        StageResult result = staged.get("db-backed");
        assertTrue(result instanceof StageResult.Cached, "external source must still be staged");
        StageResult.Cached cached = (StageResult.Cached) result;
        assertEquals("user-1", cached.scopeSegment());
        assertTrue(
                Files.isDirectory(
                        ws.resolve(MarketplaceStager.CACHE_DIR)
                                .resolve("user-1")
                                .resolve(cached.sourceNamespace())
                                .resolve("db-backed")),
                "staged directory should exist for a genuinely external source");
    }
}
