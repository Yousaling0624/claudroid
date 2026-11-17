/// Case-insensitive substring filter for the engine log viewer. A blank query
/// keeps every line, including blank ones, so the viewer still mirrors the
/// file exactly until the user starts filtering.
List<String> filterLogLines(List<String> lines, String query) {
  final needle = query.trim().toLowerCase();
  if (needle.isEmpty) return lines;
  return [
    for (final line in lines)
      if (line.toLowerCase().contains(needle)) line,
  ];
}
