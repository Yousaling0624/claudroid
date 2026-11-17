import 'package:claudroid_ui/ui/web_sources.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('a trailing source list is stripped from the answer', () {
    const text = '''
这是答案。

Sources:
- [Example](https://example.com/a)
- [第二篇](https://example.com/b)
''';
    expect(WebSources.stripTrailingSources(text), '这是答案。');
  });

  test('a sources heading followed by prose is kept', () {
    const text = '''
答案。
Sources: 这些只是额外的解释，不是列表。
''';
    expect(WebSources.stripTrailingSources(text), text);
  });

  test('a source heading inside a code fence is untouched', () {
    const text = '''
答案。

```text
Sources:
- https://example.com
```
''';
    expect(WebSources.stripTrailingSources(text), text);
  });

  test('Links JSON becomes source cards', () {
    final sources = WebSources.parse(
      'Results:\\nLinks: [{"title":"Example","url":"https://example.com/x"},'
      '{"title":"Other","url":"https://other.test/y"}]',
    );
    expect(sources, hasLength(2));
    expect(sources.first.title, 'Example');
    expect(sources.first.url, 'https://example.com/x');
    expect(sources.first.host, 'example.com');
  });

  test('markdown links become source cards', () {
    final sources = WebSources.parse(
      '见 [文档](https://docs.example.com/start) 和 [另一篇](https://other.test/x)。',
    );
    expect(sources.map((source) => source.url), [
      'https://docs.example.com/start',
      'https://other.test/x',
    ]);
  });

  test('bare urls are used when there is no other link form', () {
    final sources = WebSources.parse('见 https://bare.example.com/a.');
    expect(sources.map((source) => source.url), ['https://bare.example.com/a']);
  });

  test('duplicate urls are collapsed', () {
    final sources = WebSources.parse(
      '[A](https://example.com/x/) [B](https://example.com/x)',
    );
    expect(sources, hasLength(1));
  });
}
