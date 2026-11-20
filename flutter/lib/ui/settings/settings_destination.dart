/// Which settings page a shortcut should open directly.
///
/// The root page lists every topic; callers that already know the topic (the
/// model menu, the engine hint, the drawer's engine pill) deep-link instead of
/// dropping the user on the root and making them tap again.
enum SettingsDestination {
  root,
  serviceModels,
  engine,
  features,
  appearance,
  advanced,
  backup,
  about,
}
