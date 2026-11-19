import 'package:claudroid_ui/ui/attachments.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('splitAttachmentRefs', () {
    test('leaves plain text untouched', () {
      final split = splitAttachmentRefs('你好，帮我看看这个');
      expect(split.text, '你好，帮我看看这个');
      expect(split.paths, isEmpty);
    });

    test('collects attachment tokens and removes them from the text', () {
      final split = splitAttachmentRefs(
        '看看这两张图 @attachments/a.jpg @attachments/shot-2.png',
      );
      expect(split.text, '看看这两张图');
      expect(split.paths, ['attachments/a.jpg', 'attachments/shot-2.png']);
    });

    test('a token alone leaves no visible text', () {
      final split = splitAttachmentRefs('@attachments/photo.jpg');
      expect(split.text, '');
      expect(split.paths, ['attachments/photo.jpg']);
    });

    test('only attachment paths count as references', () {
      final split = splitAttachmentRefs(
        'mail me at a@attachments.example and @attachments/doc.pdf',
      );
      expect(split.text, 'mail me at a@attachments.example and');
      expect(split.paths, ['attachments/doc.pdf']);
    });

    test('keeps paragraph breaks between remaining prose', () {
      final split = splitAttachmentRefs(
        '第一段\n\n@attachments/a.png\n\n第二段',
      );
      expect(split.text, '第一段\n\n第二段');
      expect(split.paths, ['attachments/a.png']);
    });
  });

  group('composeChatPrompt', () {
    test('appends references on their own line', () {
      expect(
        composeChatPrompt('这是什么？', ['attachments/a.jpg']),
        '这是什么？\n\n@attachments/a.jpg',
      );
    });

    test('attachment-only prompts carry no empty text line', () {
      expect(
        composeChatPrompt('   ', ['attachments/a.jpg', 'attachments/b.png']),
        '@attachments/a.jpg @attachments/b.png',
      );
    });

    test('no attachments trims the visible text', () {
      expect(composeChatPrompt('  hello  ', const []), 'hello');
    });

    test('blank paths are ignored', () {
      expect(composeChatPrompt('hi', const ['  ']), 'hi');
    });
  });

  group('attachment helpers', () {
    test('recognises image extensions case-insensitively', () {
      expect(isImageAttachment('attachments/a.JPG'), isTrue);
      expect(isImageAttachment('attachments/a.jpeg'), isTrue);
      expect(isImageAttachment('attachments/a.webp'), isTrue);
      expect(isImageAttachment('attachments/a.heic'), isTrue);
      expect(isImageAttachment('attachments/a.zip'), isFalse);
      expect(isImageAttachment('attachments/a.jpg.txt'), isFalse);
    });

    test('file name is the last path segment', () {
      expect(attachmentName('attachments/photo.jpg'), 'photo.jpg');
      expect(attachmentName('photo.jpg'), 'photo.jpg');
    });
  });
}
