/// Unsent composer text, kept per session for the lifetime of the process.
///
/// The host persists transcripts, not drafts; this only stops a switch to the
/// drawer from throwing away half-typed text. It is deliberately in memory:
/// a draft that survived a restart would be a surprise, not a feature.
final Map<String, String> chatDrafts = {};
