package io.github.keyfire.edtbridge.edt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.egit.core.RepositoryCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionComparisonNode;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatchStatus;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessSettings;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.GitComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.V8ProjectComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.ExternalPropertyComparisonNode;
import com._1c.g5.v8.dt.md.compare.MdObjectComparisonNode;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.ServiceAccess;

import io.github.keyfire.edtbridge.core.ConfigurationComparison;
import io.github.keyfire.edtbridge.core.ConfigurationComparison.Change;
import io.github.keyfire.edtbridge.core.Jobs;

/** Reads a completed native EDT comparison while its BM session is open. */
public final class ConfigurationComparisonGateway {
    private static final ComparisonSide MAIN = ComparisonSide.MAIN;
    private static final ComparisonSide OTHER = ComparisonSide.OTHER;
    private static final ComparisonSide ANCESTOR = ComparisonSide.COMMON_ANCESTOR;
    private final ReentrantLock comparisonLock = new ReentrantLock();

    public record Source(String projectName, String revision) { }

    public ConfigurationComparison compare(Source main, Source other, Source ancestor,
            MatchingStrategy matching, int waitSeconds) throws Exception {
        if (!comparisonLock.tryLock()) {
            throw new IllegalStateException("Another bridge configuration comparison is running. Retry after it finishes.");
        }
        try {
            IComparisonDataSourceDescriptor left = descriptor(main);
            IComparisonDataSourceDescriptor right = descriptor(other);
            IComparisonDataSourceDescriptor base = ancestor == null ? null : descriptor(ancestor);
            if (left.equals(right) && base == null) return new ConfigurationComparison(List.of());

            IComparisonManager manager = ServiceAccess.get(IComparisonManager.class);
            if (manager == null) throw new IllegalStateException("EDT's comparison service is unavailable");
            ComparisonScope scope = new ComparisonScope(List.of(), List.of(), List.of());
            var handle = base == null ? new ComparisonProcessHandle(left, right, scope)
                    : new ComparisonProcessHandle(left, right, base, scope);
            if (manager.getComparisonSession(handle) != null) {
                throw new IllegalStateException("EDT already has a comparison session for these sources");
            }
            var settings = ComparisonProcessSettings.builder(matching).parseBslModuleStructure(true).build();
            return run(manager, handle, new CompareMergeProcessBatch(new CompareMergeProcessDescriptor(handle, settings)),
                    waitSeconds);
        } finally {
            comparisonLock.unlock();
        }
    }

    private static IComparisonDataSourceDescriptor descriptor(Source source) throws IOException {
        var resource = ResourcesPlugin.getWorkspace().getRoot().getProject(source.projectName());
        IV8ProjectManager projects = ServiceAccess.get(IV8ProjectManager.class);
        if (projects == null || !resource.isOpen() || !(projects.getProject(resource) instanceof IConfigurationProject project)) {
            throw new IllegalArgumentException("Open EDT configuration project required: " + source.projectName());
        }
        if (source.revision() == null) return new V8ProjectComparisonDataSourceDescriptor(project);
        return gitDescriptor(resource, source.revision());
    }

    private static GitComparisonDataSourceDescriptor gitDescriptor(IProject project, String revision) throws IOException {
        if (project.getLocation() == null) throw new IllegalArgumentException("Project has no local Git path");
        Path projectPath = project.getLocation().toFile().toPath().toRealPath();
        var builder = new FileRepositoryBuilder().findGitDir(projectPath.toFile());
        if (builder.getGitDir() == null) throw new IllegalArgumentException("Project has no Git repository: " + project.getName());
        try (var repo = RepositoryCache.INSTANCE.lookupRepository(builder.getGitDir())) {
            var commit = repo.resolve(revision + "^{commit}");
            if (commit == null) throw new IllegalArgumentException("Git revision not found: " + revision);
            Path workTree = repo.getWorkTree().toPath().toRealPath();
            if (!projectPath.startsWith(workTree)) {
                throw new IllegalArgumentException("Project is outside the Git work tree: " + project.getName());
            }
            String relative = workTree.relativize(projectPath).resolve(".project").toString().replace('\\', '/');
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
                var tree = walk.parseCommit(commit).getTree();
                try (var entry = org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, relative, tree)) {
                    if (entry == null || entry.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) {
                        throw new IllegalArgumentException("Git revision does not contain the EDT project: " + revision);
                    }
                }
            }
            return new GitComparisonDataSourceDescriptor(workTree, commit.name(), projectPath);
        }
    }

    private static ConfigurationComparison run(IComparisonManager manager, ComparisonProcessHandle handle,
            CompareMergeProcessBatch batch, int waitSeconds) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        var listener = new IComparisonManager.ICompareMergeStatusListener() {
            @Override public void statusChanged(ComparisonProcessHandle updated, ComparisonProcessStatus status) {
                if (handle.equals(updated) && status == ComparisonProcessStatus.COMPARISON_MERGE_PROCESS_CANCELLED) {
                    finished.countDown();
                }
            }

            @Override public void statusChanged(CompareMergeProcessBatch updated, CompareMergeProcessBatchStatus status) {
                if (batch.equals(updated) && status == CompareMergeProcessBatchStatus.COMPARISON_FINISHED) {
                    finished.countDown();
                }
            }
        };
        manager.addStatusListener(listener);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
            manager.startComparison(batch);
            while (!finished.await(200, TimeUnit.MILLISECONDS)) {
                if (Jobs.cancelRequested()) throw new org.eclipse.core.runtime.OperationCanceledException();
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("EDT comparison timed out after " + waitSeconds + " seconds");
                }
            }
            if (batch.getFailureCause() != null) {
                throw new IllegalStateException("Native EDT comparison failed", batch.getFailureCause());
            }
            var session = manager.getComparisonSession(handle);
            if (session == null || session.getStatus() != ComparisonProcessStatus.COMPARISON_PROCESS_FINISHED) {
                throw new IllegalStateException("EDT did not produce a completed comparison session");
            }
            return session.runComparisonTreeReadonlyTask(new IBmTask<ConfigurationComparison>() {
                @Override public String getName() { return "Read configuration comparison"; }
                @Override public Object getId() { return this; }
                @Override public Object getServiceId() { return ConfigurationComparisonGateway.class; }
                @Override public ConfigurationComparison execute(IBmTransaction transaction, IProgressMonitor monitor) {
                    return snapshot(session, monitor);
                }
            });
        } finally {
            manager.removeStatusListener(listener);
            manager.stop(handle);
        }
    }

    private static ConfigurationComparison snapshot(IComparisonSession session, IProgressMonitor monitor) {
        List<Change> objects = new ArrayList<>();
        Set<ComparisonNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collectObjects(session, session.getRootNode(), objects, seen, monitor);
        return new ConfigurationComparison(objects);
    }

    private static void collectObjects(IComparisonSession session, ComparisonNode node, List<Change> objects,
            Set<ComparisonNode> seen, IProgressMonitor monitor) {
        if (!seen.add(node)) return;
        if (monitor.isCanceled() || Jobs.cancelRequested()) throw new org.eclipse.core.runtime.OperationCanceledException();
        if (node instanceof MdObjectComparisonNode model) {
            String main = model.getMainSymlink();
            String other = model.getOtherSymlink();
            String ancestor = model.getCommonAncestorSymlink();
            ComparisonFlags flags = node.getComparisonFlags();
            String kind = ConfigurationComparison.kind(main != null, other != null, hasDifferences(flags, MAIN, OTHER));
            if (kind != null || (session.isThreeWay() && (hasDifferences(flags, MAIN, ANCESTOR)
                    || hasDifferences(flags, OTHER, ANCESTOR)))) {
                String name = other != null ? other : main != null ? main : ancestor;
                if (name != null) {
                    objects.add(new Change(name, kind == null ? "modified" : kind, main, other, ancestor,
                            flags.hasDoubleChanges(), details(session, node, new HashSet<>())));
                }
            }
        }
        for (ComparisonNode child : node.getChildren()) {
            if (!(child instanceof ExternalPropertyComparisonNode)) {
                collectObjects(session, child, objects, seen, monitor);
            }
        }
    }

    private static boolean hasDifferences(ComparisonFlags flags, ComparisonSide first, ComparisonSide second) {
        return flags.hasDifferences(first, second) || flags.hasOrderChanged(first, second);
    }

    private static List<Change> details(IComparisonSession session, ComparisonNode node, Set<Long> seen) {
        List<Change> result = new ArrayList<>();
        for (ComparisonNode child : node.getChildren()) {
            if (!seen.add(child.bmGetId())) continue;
            if (child instanceof MdObjectComparisonNode) continue;

            ComparisonFlags flags = child.getComparisonFlags();
            if (!hasDifferences(flags, MAIN, OTHER) && (!session.isThreeWay()
                    || !hasDifferences(flags, MAIN, ANCESTOR) && !hasDifferences(flags, OTHER, ANCESTOR))) continue;

            String main = null;
            String other = null;
            String ancestor = null;
            String kind = "modified";
            String name;
            if (child instanceof BslModuleSectionComparisonNode section) {
                main = section.getMainName();
                other = section.getOtherName();
                ancestor = section.getAncestorName();
                name = other != null ? other : main != null ? main : ancestor;
                String sectionKind = ConfigurationComparison.kind(main != null, other != null, true);
                if (sectionKind != null) kind = sectionKind;
                if (name == null) name = section.getSectionType().getName();
            } else {
                EStructuralFeature feature = session.getRelatedFeature(child);
                name = feature != null ? feature.getName() : child.eClass().getName();
            }
            result.add(new Change(name, kind, main, other, ancestor, flags.hasDoubleChanges(), details(session, child, seen)));
        }
        return result;
    }
}
