import '../../models/models.dart';

/// One chip in the workspace breadcrumb. [path] is workspace-relative and is
/// what the page feeds back to `workspace.list`; the root is always `/work`.
class BreadcrumbSegment {
  const BreadcrumbSegment({required this.label, required this.path});

  final String label;
  final String path;

  @override
  bool operator ==(Object other) =>
      other is BreadcrumbSegment && other.label == label && other.path == path;

  @override
  int get hashCode => Object.hash(label, path);
}

List<BreadcrumbSegment> breadcrumbSegments(String path) {
  final segments = <BreadcrumbSegment>[
    const BreadcrumbSegment(label: '/work', path: ''),
  ];
  var accumulated = '';
  for (final part in path.split('/')) {
    if (part.isEmpty) continue;
    accumulated = accumulated.isEmpty ? part : '$accumulated/$part';
    segments.add(BreadcrumbSegment(label: part, path: accumulated));
  }
  return segments;
}

/// Name-only filter; directories and files both match. A blank query keeps the
/// host's order untouched.
List<WorkspaceEntry> filterWorkspaceEntries(
  List<WorkspaceEntry> entries,
  String query,
) {
  final needle = query.trim().toLowerCase();
  if (needle.isEmpty) return entries;
  return [
    for (final entry in entries)
      if (entry.name.toLowerCase().contains(needle)) entry,
  ];
}

enum WorkspaceSortMode {
  name('名称'),
  modified('时间'),
  size('大小');

  const WorkspaceSortMode(this.label);

  final String label;
}

/// Sorts directories before files, then compares by [mode]. [ascending] flips
/// the comparison inside each group, but never puts files before directories.
/// A null [mode] means "keep the host's order" (dirs first, files by mtime
/// desc), which is why the UI offers a 默认顺序 entry.
List<WorkspaceEntry> sortWorkspaceEntries(
  List<WorkspaceEntry> entries, {
  WorkspaceSortMode? mode,
  bool ascending = true,
}) {
  if (mode == null) return entries;
  final sorted = [...entries];
  sorted.sort((a, b) {
    if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
    final result = switch (mode) {
      WorkspaceSortMode.name =>
        a.name.toLowerCase().compareTo(b.name.toLowerCase()),
      WorkspaceSortMode.modified => a.modifiedAt.compareTo(b.modifiedAt),
      WorkspaceSortMode.size => a.size.compareTo(b.size),
    };
    if (result == 0) {
      return a.name.toLowerCase().compareTo(b.name.toLowerCase());
    }
    return ascending ? result : -result;
  });
  return sorted;
}
