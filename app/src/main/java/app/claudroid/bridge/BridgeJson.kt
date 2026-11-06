package app.claudroid.bridge

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration shared by the bridge and `SessionStore`
 * (docs/BRIDGE_CONTRACT.md §3).
 *
 * It has to match the on-disk format exactly: the Flutter frontend decodes the same
 * strings the app writes to disk, and the default class discriminator `"type"` is
 * what `Block`'s `@SerialName`s rely on.
 */
object BridgeJson {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
}
