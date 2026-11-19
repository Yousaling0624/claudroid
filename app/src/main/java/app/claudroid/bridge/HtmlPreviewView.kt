package app.claudroid.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.common.StandardMethodCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Workspace HTML preview as a real WebView inside a Flutter platform view.
 *
 * `webview_flutter`'s Android plugin cannot be configured in this add-to-app module
 * (it applies `kotlin-android`, which AGP 9 rejects, or uses Kotlin DSL accessors
 * that cannot see Flutter's extension here). Hosting the WebView ourselves also
 * keeps the release APK free of a plugin we use on one screen.
 *
 * The document is loaded with a null base URL and file/content access off, matching
 * `ui/workspace/WorkspaceScreen.kt`: scripts may run, but they cannot read files or
 * content providers under the app's private directory.
 */
internal class HtmlPreviewView(
    private val context: Context,
    private val id: Int,
    creationParams: Any?,
    messenger: BinaryMessenger,
) : PlatformView {

    private val html = (creationParams as? Map<*, *>)?.get("html") as? String ?: ""
    private val channel = MethodChannel(messenger, "$VIEW_TYPE/$id", StandardMethodCodec.INSTANCE)
    private var readySent = false

    @SuppressLint("SetJavaScriptEnabled")
    private val webView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (readySent) return
                readySent = true
                channel.invokeMethod("ready", null)
            }
        }
        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    override fun getView(): View = webView

    override fun dispose() {
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.destroy()
        channel.setMethodCallHandler(null)
    }

    internal class Factory(
        private val messenger: BinaryMessenger,
    ) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context, id: Int, args: Any?): PlatformView =
            HtmlPreviewView(context, id, args, messenger)
    }

    companion object {
        const val VIEW_TYPE = "app.claudroid/html_preview"
    }
}
