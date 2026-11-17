import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/ui/workspace_logic.dart';
import 'package:flutter_test/flutter_test.dart';

WorkspaceEntry _entry(
  String name, {
  bool directory = false,
  int size = 0,
  int modifiedAt = 0,
}) =>
    WorkspaceEntry(
      name: name,
      path: name,
      kind: directory ? WorkspaceKind.directory : WorkspaceKind.text,
      size: size,
      modifiedAt: modifiedAt,
      isDirectory: directory,
    );

void main() {
  group('breadcrumbSegments', () {
    test('the root is a single /work chip', () {
      final segments = breadcrumbSegments('');
      expect(segments, hasLength(1));
      expect(segments.single.label, '/work');
      expect(segments.single.path, '');
    });

    test('nested directories accumulate workspace-relative paths', () {
      final segments = breadcrumbSegments('src/ui/widgets');
      expect(segments.map((s) => s.label), ['/work', 'src', 'ui', 'widgets']);
      expect(segments.map((s) => s.path), ['', 'src', 'src/ui', 'src/ui/widgets']);
    });

    test('leading and repeated separators are ignored', () {
      final segments = breadcrumbSegments('/src//ui/');
      expect(segments.map((s) => s.label), ['/work', 'src', 'ui']);
    });
  });

  group('filterWorkspaceEntries', () {
    final entries = [
      _entry('README.md', size: 10),
      _entry('src', directory: true),
      _entry('build.log', size: 20),
    ];

    test('a blank query keeps the host order', () {
      expect(filterWorkspaceEntries(entries, '  '), same(entries));
    });

    test('matches names case-insensitively', () {
      expect(filterWorkspaceEntries(entries, 'readme').map((e) => e.name), [
        'README.md',
      ]);
      expect(filterWorkspaceEntries(entries, 'r').map((e) => e.name), [
        'README.md',
        'src',
      ]);
    });
  });

  group('sortWorkspaceEntries', () {
    final entries = [
      _entry('beta.txt', size: 30, modifiedAt: 300),
      _entry('alpha', directory: true, modifiedAt: 100),
      _entry('gamma.txt', size: 10, modifiedAt: 200),
      _entry('delta', directory: true, modifiedAt: 400),
    ];

    test('no mode means the host order', () {
      expect(sortWorkspaceEntries(entries), same(entries));
    });

    test('name mode keeps directories first', () {
      expect(sortWorkspaceEntries(entries, mode: WorkspaceSortMode.name)
          .map((e) => e.name), ['alpha', 'delta', 'beta.txt', 'gamma.txt']);
    });

    test('time descends when ascending is false but directories stay first', () {
      expect(
        sortWorkspaceEntries(
          entries,
          mode: WorkspaceSortMode.modified,
          ascending: false,
        ).map((e) => e.name),
        ['delta', 'alpha', 'beta.txt', 'gamma.txt'],
      );
    });

    test('size mode compares inside each group', () {
      expect(sortWorkspaceEntries(entries, mode: WorkspaceSortMode.size)
          .map((e) => e.name), ['alpha', 'delta', 'gamma.txt', 'beta.txt']);
    });

    test('equal keys fall back to name in both directions', () {
      final ties = [
        _entry('b.txt', size: 5),
        _entry('a.txt', size: 5),
      ];
      expect(
        sortWorkspaceEntries(ties, mode: WorkspaceSortMode.size)
            .map((e) => e.name),
        ['a.txt', 'b.txt'],
      );
      expect(
        sortWorkspaceEntries(ties, mode: WorkspaceSortMode.size, ascending: false)
            .map((e) => e.name),
        ['a.txt', 'b.txt'],
      );
    });
  });
}
