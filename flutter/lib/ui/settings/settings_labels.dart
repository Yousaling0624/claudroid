import '../../models/models.dart';

const apiFlavorLabels = {'ANTHROPIC': 'Anthropic 兼容', 'OPENAI': 'OpenAI 兼容'};

const authStyleLabels = {
  'BEARER': 'Authorization: Bearer',
  'API_KEY': 'x-api-key',
  'BOTH': '两个都发',
};

const permissionLabels = {
  'AUTO': '自动',
  'BYPASS': '完全放行',
  'ACCEPT_EDITS': '自动改文件',
  'MANUAL': '默认（只读）',
  'PLAN': '只读规划',
};

/// The one-line caution under the composer. Mirrors the Compose composer's
/// note, but makes the fully-bypassed mode explicit.
String permissionFootnote(String mode) => mode == 'BYPASS'
    ? '完全放行：Agent 的命令会直接执行，请确认任务来源可信。'
    : 'Agent 可能修改系统文件，请确认它执行的命令。';

/// Remote-turn permission modes, in the order the host pickers list them.
const remotePermissionLabels = {
  'auto': '自动',
  'default': '每次确认',
  'acceptEdits': '自动改文件',
  'plan': '只读规划',
  'bypassPermissions': '完全放行',
};

/// The composer note for a remote turn. Kept here so the home and chat
/// footnotes cannot drift apart; each mode says what actually happens on the
/// host instead of lumping edit approvals in with forwarded confirmations.
String remotePermissionFootnote(String mode) => switch (mode) {
      'bypassPermissions' =>
        '远程主机执行；完全放行：命令会在主机上直接执行，请确认主机与任务可信。',
      'auto' => '远程主机执行；由主机的 Claude Auto 模式审核敏感操作，不再逐条确认。',
      'default' => '远程主机执行；工具调用会转发到手机确认。',
      'acceptEdits' => '远程主机执行；文件修改会自动通过，其他工具调用会转发到手机确认。',
      'plan' => '远程主机执行；只读规划，不会执行修改。',
      _ => '远程主机执行；工具调用会转发到手机确认。',
    };

const effortLabels = {
  'AUTO': '自动',
  'LOW': '低',
  'MEDIUM': '中',
  'HIGH': '高',
  'XHIGH': '很高',
  'MAX': '最高',
};

const engineModeLabels = {'AUTO': '自动', 'PROOT': 'Proot', 'ROOT': 'Root'};

const themeLabels = {0: '跟随系统', 1: '浅色', 2: '深色'};

const featureFlagDefaults = {
  'agentTeams': false,
  'workflows': true,
  'autoMemory': true,
  'backgroundTasks': true,
  'extendedThinking': true,
};

String enumLabel<K>(Map<K, String> labels, K key) => labels[key] ?? '$key';

String tierField(String tier) => switch (tier) {
  'OPUS' => 'opusModel',
  'FABLE' => 'fableModel',
  'SONNET' => 'sonnetModel',
  'HAIKU' => 'haikuModel',
  _ => 'model',
};

String tierLabel(String tier) => switch (tier) {
  '' => 'CLI 默认（不指定模型）',
  'AUTO' => '自动（按任务路由）',
  _ => modelTierLabels[tier] ?? tier,
};

String releaseStatusLabel(ReleaseStatus status) => switch (status) {
  ReleaseStatus.upToDate => '已是最新',
  ReleaseStatus.updateAvailable => '有新版本可下载',
  ReleaseStatus.devBuild => '当前是本地构建',
};

String formatBytes(int bytes) {
  if (bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  var value = bytes.toDouble();
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return '${value.toStringAsFixed(unit == 0 ? 0 : 1)} ${units[unit]}';
}

/// Empty means "follow the system proxy"; anything else is a manual
/// HTTP/HTTPS proxy the CLI should use (`networkProxy`, read by Kotlin).
String networkProxyLabel(String proxy) {
  if (proxy.isEmpty) return '自动（跟随系统）';
  if (proxy.contains('@')) return '已设置（含凭据）';
  return proxy;
}

String? validateNetworkProxy(String? value) {
  final text = (value ?? '').trim();
  if (text.isEmpty) return null;
  if (text.toLowerCase().startsWith('socks')) {
    return '只支持 HTTP/HTTPS 代理（Clash 请用混合端口）';
  }
  final uri = Uri.tryParse(text.contains('://') ? text : 'http://$text');
  if (uri == null ||
      (uri.scheme != 'http' && uri.scheme != 'https') ||
      uri.host.isEmpty ||
      (uri.hasPort && (uri.port < 1 || uri.port > 65535))) {
    return '请填写 http://主机:端口';
  }
  return null;
}
