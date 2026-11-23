import 'models.dart';

/// The current execution host scopes the app: the drawer, the home page's
/// recent card and every new chat show only that host's conversations.
///
/// `null` (or an empty string) always means the local PRoot engine. Sessions
/// written before remote hosts existed have no `hostId`, so they belong to the
/// local scope instead of disappearing from both lists.
String? normalizeHostId(String? value) {
  final trimmed = value?.trim() ?? '';
  return trimmed.isEmpty ? null : trimmed;
}

bool sameHost(String? a, String? b) => normalizeHostId(a) == normalizeHostId(b);

List<ChatSession> sessionsForHost(List<ChatSession> sessions, String? hostId) {
  final scope = normalizeHostId(hostId);
  return [
    for (final session in sessions)
      if (sameHost(session.hostId, scope)) session,
  ];
}
