import 'package:claudroid_ui/models/models.dart';
import 'package:claudroid_ui/ui/claude_dir_dialog.dart';
import 'package:flutter_test/flutter_test.dart';

WorkspaceEntry _entry(String name, {bool directory = true}) => WorkspaceEntry(
      name: name,
      path: name,
      kind: directory ? WorkspaceKind.directory : WorkspaceKind.text,
      size: 0,
      modifiedAt: 0,
      isDirectory: directory,
    );

void main() {
  group('claudeDirLabel', () {
    test('root and nested paths use the container mount point', () {
      expect(claudeDirLabel(''), '/work');
      expect(claudeDirLabel('foo'), '/work/foo');
      expect(claudeDirLabel('foo/bar'), '/work/foo/bar');
    });

    test('stray slashes and whitespace do not leak into the label', () {
      expect(claudeDirLabel('/foo/bar/'), '/work/foo/bar');
      expect(claudeDirLabel('foo//bar'), '/work/foo/bar');
      expect(claudeDirLabel('  '), '/work');
    });
  });

  group('parentClaudeDir', () {
    test('walks one level up and stops at the workspace root', () {
      expect(parentClaudeDir(''), '');
      expect(parentClaudeDir('foo'), '');
      expect(parentClaudeDir('foo/bar'), 'foo');
      expect(parentClaudeDir('foo/bar/baz'), 'foo/bar');
    });

    test('normalizes before walking up', () {
      expect(parentClaudeDir('/foo/bar/'), 'foo');
      expect(parentClaudeDir('//foo//bar//'), 'foo');
    });
  });

  group('claudeDirChildren', () {
    test('keeps directories only and sorts names case-insensitively', () {
      final children = claudeDirChildren(
        WorkspaceListing(
          path: '',
          totalBytes: 0,
          entries: [
            _entry('zeta'),
            _entry('notes.txt', directory: false),
            _entry('Alpha'),
            _entry('beta'),
          ],
        ),
      );

      expect(
        [for (final entry in children) entry.name],
        ['Alpha', 'beta', 'zeta'],
      );
    });

    test('a file-only listing has no children', () {
      final children = claudeDirChildren(
        const WorkspaceListing(
          path: '',
          totalBytes: 0,
          entries: [
            WorkspaceEntry(
              name: 'README.md',
              path: 'README.md',
              kind: WorkspaceKind.markdown,
              size: 12,
              modifiedAt: 0,
              isDirectory: false,
            ),
          ],
        ),
      );

      expect(children, isEmpty);
    });

    test('an empty listing has no children', () {
      expect(
        claudeDirChildren(
          const WorkspaceListing(path: 'foo', entries: [], totalBytes: 0),
        ),
        isEmpty,
      );
    });
  });
}
