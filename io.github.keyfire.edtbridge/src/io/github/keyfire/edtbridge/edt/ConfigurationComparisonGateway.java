package io.github.keyfire.edtbridge.edt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EClass;
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
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.ExternalPropertyComparisonNode;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com._1c.g5.v8.dt.compare.model.UnsupportedObjectComparisonNode;
import com._1c.g5.v8.dt.md.compare.DistributionSettingsComparisonNode;
import com._1c.g5.v8.dt.md.compare.MdObjectComparisonNode;
import com._1c.g5.v8.dt.md.compare.SupportSettingsComparisonNode;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

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
    private static final ReentrantLock COMPARISON_LOCK = new ReentrantLock();

    public record Source(String projectName, String revision) { }

    record SupplierTarget(String fqn, EClass type, UUID userId, UUID supplierId) { }

    public ConfigurationComparison compare(Source main, Source other, Source ancestor,
            MatchingStrategy matching, int waitSeconds) throws Exception {
        if (!COMPARISON_LOCK.tryLock()) {
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
                    waitSeconds, ConfigurationComparisonGateway::snapshot);
        } finally {
            COMPARISON_LOCK.unlock();
        }
    }

    static void requireSupplierMatch(String projectName, IComparisonDataSourceDescriptor supplier,
            List<SupplierTarget> targets) throws Exception {
        if (!COMPARISON_LOCK.tryLock()) {
            throw new IllegalStateException("Another bridge configuration comparison is running. Retry after it finishes.");
        }
        try {
            var manager = ServiceAccess.get(IComparisonManager.class);
            if (manager == null) throw new IllegalStateException("EDT's comparison service is unavailable");
            var fqns = targets.stream().map(SupplierTarget::fqn).toList();
            var scope = new ComparisonScope(fqns, fqns);
            var handle = new ComparisonProcessHandle(descriptor(new Source(projectName, null)), supplier, scope);
            if (manager.getComparisonSession(handle) != null) {
                throw new IllegalStateException("EDT already has a comparison session for these sources");
            }
            var settings = ComparisonProcessSettings.builder(MatchingStrategy.UUID_THEN_NAME)
                    .parseBslModuleStructure(false).build();
            run(manager, handle, new CompareMergeProcessBatch(new CompareMergeProcessDescriptor(handle, settings)),
                    120, (session, monitor) -> {
                        for (SupplierTarget target : targets) {
                            requireMatchingObject(session, target);
                        }
                        return null;
                    });
        } finally {
            COMPARISON_LOCK.unlock();
        }
    }

    private static void requireMatchingObject(IComparisonSession session, SupplierTarget target) {
        var node = session.getTopNode(target.fqn(), MAIN);
        if (node == null || !session.isInInputScope(node) || node.getComparisonStatus() != ComparisonNodeStatus.FINISHED
                || node.getMainSymlink() == null || node.getOtherSymlink() == null) {
            throw new IllegalArgumentException("EDT did not finish comparing both versions of " + target.fqn());
        }
        var main = session.getDataSource(MAIN);
        var other = session.getDataSource(OTHER);
        var left = main.beginTransaction();
        try {
            var right = other.beginTransaction();
            try {
                var user = main.getTopLevelObject(node.getMainSymlink(), target.type());
                var parent = other.getTopLevelObject(node.getOtherSymlink(), target.type());
                if (!(user instanceof MdObject userObject) || !target.userId().equals(userObject.getUuid())
                        || !(parent instanceof MdObject supplierObject) || !target.supplierId().equals(supplierObject.getUuid())) {
                    throw new IllegalArgumentException("EDT did not match the recorded supplier object for " + target.fqn());
                }
            } finally {
                if (right != null) right.close();
            }
        } finally {
            if (left != null) left.close();
        }
        var difference = supplierContentDifference(node);
        if (difference != null) {
            var property = session.getRelatedFeature(difference);
            String detail = property == null ? difference.eClass().getName() : property.getName();
            throw new IllegalArgumentException("Object differs from the supplier version: " + target.fqn()
                    + " (" + detail + ")");
        }
    }

    /** A rule present on only one side may contribute its presence flags to the owning object. */
    static ComparisonNode supplierContentDifference(ComparisonNode node) {
        if (node instanceof UnsupportedObjectComparisonNode) return node;
        if (node instanceof SupportSettingsComparisonNode || node instanceof DistributionSettingsComparisonNode) return null;
        if (node instanceof TopComparisonNode top && top.getComparisonStatus() != ComparisonNodeStatus.FINISHED) return node;
        if (node instanceof MdObjectComparisonNode object
                && (object.getMainSymlink() == null || object.getOtherSymlink() == null)) return node;
        ComparisonFlags flags = node.getComparisonFlags();
        if (flags == null || flags.hasOrderChanged(MAIN, OTHER)) return node;
        ComparisonFlags childFlags = new ComparisonFlags();
        for (ComparisonNode child : node.getChildren()) {
            var difference = supplierContentDifference(child);
            if (difference != null) return difference;
            if (child.getComparisonFlags() != null) childFlags.addFlags(child.getComparisonFlags());
        }
        if (flags.hasChanged(MAIN, OTHER)
                || (flags.isOnOneSide(MAIN, OTHER) && !childFlags.isOnOneSide(MAIN, OTHER))
                || (flags.isOnOneSide(OTHER, MAIN) && !childFlags.isOnOneSide(OTHER, MAIN))) return node;
        return null;
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

    private static <T> T run(IComparisonManager manager, ComparisonProcessHandle handle,
            CompareMergeProcessBatch batch, int waitSeconds, BiFunction<IComparisonSession, IProgressMonitor, T> read) throws Exception {
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
            Jobs.stage("comparing configurations");
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
            Jobs.stage("reading configuration differences");
            return session.runComparisonTreeReadonlyTask(new IBmTask<T>() {
                @Override public String getName() { return "Read configuration comparison"; }
                @Override public Object getId() { return this; }
                @Override public Object getServiceId() { return ConfigurationComparisonGateway.class; }
                @Override public T execute(IBmTransaction transaction, IProgressMonitor monitor) {
                    return read.apply(session, monitor);
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
