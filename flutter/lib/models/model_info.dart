/// One entry of `models.list`, including the metadata a gateway may add.
///
/// The bridge always sends `modelsJson` (plain IDs) for compatibility; the
/// richer `modelDetailsJson` is optional, so [ModelInfo.idOnly] keeps older
/// endpoints and the Compose fallback working.
class ModelInfo {
  const ModelInfo({
    required this.id,
    this.displayName = '',
    this.contextWindow,
    this.maxOutputTokens,
    this.maxInputTokens,
    this.ownedBy = '',
    this.reasoning = false,
    this.reasoningLevels = const [],
    this.label = '',
    this.modalities = const [],
    this.supportedParameters = const [],
  });

  factory ModelInfo.fromJson(Map<String, dynamic> json) {
    return ModelInfo(
      id: json['id'] as String? ?? '',
      displayName: json['displayName'] as String? ?? '',
      contextWindow: _int(json['contextWindow']),
      maxOutputTokens: _int(json['maxOutputTokens']),
      maxInputTokens: _int(json['maxInputTokens']),
      ownedBy: json['ownedBy'] as String? ?? '',
      reasoning: json['reasoning'] as bool? ?? false,
      reasoningLevels: _strings(json['reasoningLevels']),
      label: json['label'] as String? ?? '',
      modalities: _strings(json['modalities']),
      supportedParameters: _strings(json['supportedParameters']),
    );
  }

  factory ModelInfo.idOnly(String id) => ModelInfo(id: id);

  final String id;
  final String displayName;
  final int? contextWindow;
  final int? maxOutputTokens;
  final int? maxInputTokens;
  final String ownedBy;
  final bool reasoning;
  final List<String> reasoningLevels;
  final String label;
  final List<String> modalities;
  final List<String> supportedParameters;

  String get title => displayName.isEmpty ? id : displayName;

  /// Whether the picker has anything beyond the ID to show.
  bool get hasMeta =>
      displayName.isNotEmpty ||
      contextWindow != null ||
      maxOutputTokens != null ||
      ownedBy.isNotEmpty ||
      reasoningLevels.isNotEmpty;

  static int? _int(Object? value) => switch (value) {
    final num number => number.toInt(),
    final String text => int.tryParse(text),
    _ => null,
  };

  static List<String> _strings(Object? value) {
    if (value is! List) return const [];
    return [
      for (final item in value)
        if (item is String && item.isNotEmpty) item,
    ];
  }
}
