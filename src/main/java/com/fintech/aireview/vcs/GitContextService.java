package com.fintech.aireview.vcs;

import com.fintech.aireview.settings.AiReviewSettings;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads local changes, staged changes, commits and branch diffs straight out of the
 * repository that the IDE already has open. Uses plain git plumbing through Git4Idea
 * so it behaves identically in IntelliJ IDEA and WebStorm.
 */
@Service(Service.Level.PROJECT)
public final class GitContextService {

    private static final String UNTRACKED_PREFIX = "--- NEW UNTRACKED FILE: ";
    private static final String UNTRACKED_SUFFIX = " ---";

    private final Project project;

    public GitContextService(Project project) {
        this.project = project;
    }

    public GitRepository repository() {
        List<GitRepository> repos = GitRepositoryManager.getInstance(project).getRepositories();
        if (repos.isEmpty()) throw new GitContextException("No Git repository found in this project.");
        return repos.get(0);
    }

    public String currentBranch() {
        try {
            String name = repository().getCurrentBranchName();
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    public ReviewContext collect(ReviewSource source, AiReviewSettings.State state, Consumer<String> trace) {
        if (source == ReviewSource.CURRENT_FILE) return currentFileContext(state, trace);

        GitRepository repo = repository();
        VirtualFile root = repo.getRoot();
        String branchName = repo.getCurrentBranchName();
        trace.accept("Repository: " + root.getPath() + "  (branch: " + (branchName != null ? branchName : "detached") + ")");

        String diff = switch (source) {
            case UNCOMMITTED -> {
                String tracked = git(root, GitCommand.DIFF, "HEAD", "--unified=6", "--no-color");
                String untracked = state.includeUntrackedFiles ? untrackedFiles(root, trace) : "";
                yield (tracked + "\n" + untracked).trim();
            }
            case STAGED -> git(root, GitCommand.DIFF, "--staged", "--unified=6", "--no-color");
            case LAST_COMMIT -> git(root, GitCommand.SHOW, "HEAD", "--unified=6", "--no-color", "--stat", "--patch");
            case LAST_N_COMMITS -> {
                int n = Math.max(1, Math.min(20, state.commitCount));
                yield git(root, GitCommand.DIFF, "HEAD~" + n + "..HEAD", "--unified=6", "--no-color");
            }
            case BRANCH_VS_BASE -> {
                String base = state.baseBranch.isBlank() ? "origin/main" : state.baseBranch;
                trace.accept("Diffing against base branch '" + base + "' (three-dot / merge-base diff).");
                yield git(root, GitCommand.DIFF, base + "...HEAD", "--unified=6", "--no-color");
            }
            case CURRENT_FILE -> "";
        };

        if (diff.isBlank()) {
            return new ReviewContext(source, "No changes found for '" + source.getDisplayName() + "'.", "", List.of(), false);
        }

        List<String> files = parseChangedFiles(diff);
        trace.accept("Collected " + files.size() + " changed file(s), " + diff.length() + " characters of diff.");

        Truncated t = truncate(diff, state.maxContextChars);
        if (t.truncated()) trace.accept("Diff truncated to " + state.maxContextChars + " characters to fit the model context.");

        StringBuilder header = new StringBuilder();
        header.append("Review source: ").append(source.getDisplayName()).append('\n');
        header.append("Repository: ").append(root.getName()).append('\n');
        header.append("Branch: ").append(branchName != null ? branchName : "detached HEAD").append('\n');
        header.append("Changed files (").append(files.size()).append("):\n");
        for (String f : files) header.append("  - ").append(f).append('\n');

        return new ReviewContext(source, header.toString(), t.text(), files, t.truncated());
    }

    private ReviewContext currentFileContext(AiReviewSettings.State state, Consumer<String> trace) {
        VirtualFile file = ReadAction.compute(() -> {
            VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
            return selected.length > 0 ? selected[0] : null;
        });
        if (file == null) throw new GitContextException("No file is open in the editor.");

        String text = ReadAction.compute(() -> readText(file));
        trace.accept("Reviewing whole file: " + file.getPath() + " (" + text.length() + " characters).");

        Truncated t = truncate(text, state.maxContextChars);
        String header = "Review source: whole file\nFile: " + file.getPath() + "\n";
        String body = "```\n// FILE: " + file.getName() + "\n" + t.text() + "\n```";
        return new ReviewContext(ReviewSource.CURRENT_FILE, header, body, List.of(file.getPath()), t.truncated());
    }

    private String untrackedFiles(VirtualFile root, Consumer<String> trace) {
        String listing = git(root, GitCommand.LS_FILES, "--others", "--exclude-standard");
        List<String> names = new ArrayList<>();
        for (String line : listing.split("\\R")) {
            String name = line.trim();
            if (!name.isEmpty()) names.add(name);
        }
        if (names.isEmpty()) return "";
        trace.accept("Including " + names.size() + " untracked file(s).");

        StringBuilder sb = new StringBuilder();
        for (String name : names.subList(0, Math.min(20, names.size()))) {
            VirtualFile vf = root.findFileByRelativePath(name);
            if (vf == null || vf.isDirectory() || vf.getLength() > 200_000) continue;
            String content;
            try {
                content = ReadAction.compute(() -> readText(vf));
            } catch (Exception e) {
                continue;
            }
            sb.append('\n').append(UNTRACKED_PREFIX).append(name).append(UNTRACKED_SUFFIX).append('\n');
            sb.append(content).append('\n');
        }
        return sb.toString();
    }

    private static String readText(VirtualFile file) {
        try {
            Charset charset = file.getCharset();
            return new String(file.contentsToByteArray(), charset);
        } catch (java.io.IOException e) {
            throw new GitContextException("Could not read " + file.getPath() + ": " + e.getMessage());
        }
    }

    private String git(VirtualFile root, GitCommand command, String... params) {
        GitLineHandler handler = new GitLineHandler(project, root, command);
        handler.addParameters(params);
        handler.setSilent(true);
        GitCommandResult result = Git.getInstance().runCommand(handler);
        if (!result.success()) {
            throw new GitContextException("git " + command.name() + " failed: " + result.getErrorOutputAsJoinedString());
        }
        return result.getOutputAsJoinedString();
    }

    private static List<String> parseChangedFiles(String diff) {
        Set<String> files = new LinkedHashSet<>();
        for (String line : diff.split("\\R")) {
            if (line.startsWith("+++ b/")) {
                files.add(line.substring("+++ b/".length()).trim());
            } else if (line.startsWith(UNTRACKED_PREFIX)) {
                String name = line.substring(UNTRACKED_PREFIX.length());
                if (name.endsWith(UNTRACKED_SUFFIX)) name = name.substring(0, name.length() - UNTRACKED_SUFFIX.length());
                files.add(name.trim());
            }
        }
        files.remove("/dev/null");
        return new ArrayList<>(files);
    }

    private static Truncated truncate(String text, int max) {
        int limit = Math.max(4000, max);
        if (text.length() <= limit) return new Truncated(text, false);
        return new Truncated(text.substring(0, limit) + "\n\n[... context truncated by the plugin ...]", true);
    }

    private record Truncated(String text, boolean truncated) {
    }
}
