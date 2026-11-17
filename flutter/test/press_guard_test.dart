import 'package:claudroid_ui/ui/press_guard.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:prism_ui/prism_ui.dart';

/// A prism_ui button that turns disabled from its own tap used to trip
/// `assert(!_dirty)`: `PressSurface` cancels a running press inside the very
/// update that disables the button and reports it through the button's states
/// controller, which calls `setState` mid-update. `DeferredDisable` holds the
/// callback through the press-release window so the animation can finish.
void main() {
  testWidgets('a self-disabling button does not throw', (tester) async {
    final key = GlobalKey<_HostState>();
    await tester.pumpWidget(_app(_Host(key: key)));

    await tester.tap(find.text('go'));
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(
      key.currentState!.callback,
      isNotNull,
      reason: 'held during the press release',
    );

    // The hold ends after the 250 ms release and the button really disables.
    await tester.pump(const Duration(milliseconds: 400));
    expect(tester.takeException(), isNull);
    expect(key.currentState!.callback, isNull);
  });

  testWidgets('tapping during the hold does not run the action twice', (
    tester,
  ) async {
    final key = GlobalKey<_HostState>();
    await tester.pumpWidget(_app(_Host(key: key)));

    await tester.tap(find.text('go'));
    await tester.pump();
    await tester.tap(find.text('go'));
    await tester.pump();

    expect(key.currentState!.taps, 1);
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pump(const Duration(milliseconds: 400));
    expect(tester.takeException(), isNull);
    expect(key.currentState!.callback, isNull);
  });
}

Widget _app(Widget child) => PrismApp(
  theme: ThemeData(
    colorScheme: seededColorScheme(
      seedColor: const Color(0xFFD97757),
      brightness: Brightness.light,
    ),
  ),
  home: child,
);

class _Host extends StatefulWidget {
  const _Host({super.key});

  @override
  State<_Host> createState() => _HostState();
}

class _HostState extends State<_Host> {
  bool busy = false;
  int taps = 0;
  VoidCallback? callback;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: DeferredDisable(
        onPressed: busy
            ? null
            : () {
                taps++;
                setState(() => busy = true);
              },
        builder: (context, onPressed) {
          callback = onPressed;
          return FilledButton(onPressed: onPressed, child: const Text('go'));
        },
      ),
    );
  }
}
