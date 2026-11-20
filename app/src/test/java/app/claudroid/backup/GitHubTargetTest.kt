package app.claudroid.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * GitHub Contents API wire details (docs/BACKUP.md §5.3). Real response samples are
 * parsed by the pure functions; no network is involved.
 */
class GitHubTargetTest {

    private val contentsSample = """
        {
          "name": "claudroid-backup.zip",
          "path": "dir/claudroid-backup.zip",
          "sha": "95b966ae1c166bd92f8ae7d1c313e738c731dfc3",
          "size": 12,
          "url": "https://api.github.com/repos/owner/name/contents/dir/claudroid-backup.zip",
          "html_url": "https://github.com/owner/name/blob/main/dir/claudroid-backup.zip",
          "git_url": "https://api.github.com/repos/owner/name/git/blobs/95b966ae1c166bd92f8ae7d1c313e738c731dfc3",
          "download_url": "https://raw.githubusercontent.com/owner/name/main/dir/claudroid-backup.zip",
          "type": "file",
          "content": "aGVsbG8gd29ybGQK\n",
          "encoding": "base64",
          "_links": {
            "self": "https://api.github.com/repos/owner/name/contents/dir/claudroid-backup.zip",
            "git": "https://api.github.com/repos/owner/name/git/blobs/95b966ae1c166bd92f8ae7d1c313e738c731dfc3",
            "html": "https://github.com/owner/name/blob/main/dir/claudroid-backup.zip"
          }
        }
    """.trimIndent()

    @Test
    fun `contents url encodes every path segment and the ref`() {
        val config = GithubConfig(
            repo = "owner/name",
            branch = "feature/x y",
            path = "/dir/claudroid backup.zip",
            token = "t",
        )
        assertEquals(
            "https://api.github.com/repos/owner/name/contents/dir/claudroid%20backup.zip" +
                "?ref=feature/x%20y",
            GitHubTarget.contentsUrl(config, withRef = true),
        )
        assertEquals(
            "https://api.github.com/repos/owner/name/contents/dir/claudroid%20backup.zip",
            GitHubTarget.contentsUrl(config, withRef = false),
        )
    }

    @Test
    fun `blank branch and path fall back to the documented defaults`() {
        val config = GithubConfig(repo = "owner/name", branch = " ", path = "")
        assertEquals(GitHubTarget.DEFAULT_BRANCH, GitHubTarget.branch(config))
        assertEquals(GitHubTarget.DEFAULT_PATH, GitHubTarget.filePath(config))
    }

    @Test
    fun `repo must be owner slash name`() {
        assertThrows(IllegalArgumentException::class.java) { GitHubTarget.repoPath("owner") }
        assertThrows(IllegalArgumentException::class.java) { GitHubTarget.repoPath("a/b/c") }
        assertEquals("owner/name", GitHubTarget.repoPath(" owner/name "))
    }

    @Test
    fun `real contents response is parsed including newline wrapped base64`() {
        val parsed = GitHubTarget.parseContentsResponse(contentsSample)
        assertEquals("95b966ae1c166bd92f8ae7d1c313e738c731dfc3", parsed?.sha)
        assertEquals(12L, parsed?.size)
        assertEquals("base64", parsed?.encoding)
        assertEquals("aGVsbG8gd29ybGQK\n", parsed?.content)
        val content = parsed?.content ?: ""
        assertEquals(
            "hello world\n",
            String(java.util.Base64.getMimeDecoder().decode(content), Charsets.UTF_8),
        )
    }

    @Test
    fun `malformed or non object bodies never throw`() {
        assertNull(GitHubTarget.parseContentsResponse("[]"))
        assertNull(GitHubTarget.parseContentsResponse("<html>proxy error</html>"))
        assertNull(GitHubTarget.parseContentsResponse(""))
        assertNull(GitHubTarget.shaFromBody("not json"))
    }

    @Test
    fun `sha is recovered from a response truncated after the sha field`() {
        val sha = "95b966ae1c166bd92f8ae7d1c313e738c731dfc3"
        val truncated = """{"name":"claudroid-backup.zip","sha":"$sha","content":"QUJD"""
        assertEquals(sha, GitHubTarget.shaFromBody(truncated))
    }

    @Test
    fun `put body carries message content branch and optional sha as json`() {
        val body = GitHubTarget.putBody("Claudroid backup 2024-01-02 03:04", "QUJD", "main", "abc123")
        assertFalse("base64 must not contain line breaks", body.contains("\n"))
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("Claudroid backup 2024-01-02 03:04", obj.getValue("message").jsonPrimitive.content)
        assertEquals("QUJD", obj.getValue("content").jsonPrimitive.content)
        assertEquals("main", obj.getValue("branch").jsonPrimitive.content)
        assertEquals("abc123", obj.getValue("sha").jsonPrimitive.content)

        val first = Json.parseToJsonElement(GitHubTarget.putBody("m", "", "main", null)).jsonObject
        assertFalse(first.containsKey("sha"))
        assertEquals("", first.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `message and branch are json escaped`() {
        val body = GitHubTarget.putBody("a\"b\\c", "QUJD", "dev\"x", null)
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("a\"b\\c", obj.getValue("message").jsonPrimitive.content)
        assertEquals("dev\"x", obj.getValue("branch").jsonPrimitive.content)
    }

    @Test
    fun `base64 length matches the padding rules`() {
        assertEquals(0L, GitHubTarget.base64Length(0))
        assertEquals(4L, GitHubTarget.base64Length(1))
        assertEquals(4L, GitHubTarget.base64Length(2))
        assertEquals(4L, GitHubTarget.base64Length(3))
        assertEquals(8L, GitHubTarget.base64Length(4))
        assertEquals(8L, GitHubTarget.base64Length(6))
    }

    @Test
    fun `archives above the github limit are refused with the next step`() {
        GitHubTarget.requireArchiveSize(GitHubTarget.MAX_ARCHIVE_BYTES)
        val ex = assertThrows(IllegalStateException::class.java) {
            GitHubTarget.requireArchiveSize(GitHubTarget.MAX_ARCHIVE_BYTES + 1)
        }
        assertEquals("归档超过 95 MB，GitHub 仓库放不下，请改用 WebDAV 或 S3", ex.message)
    }

    @Test
    fun `commit message uses the documented timestamp format`() {
        val at = LocalDateTime.of(2024, 1, 2, 3, 4)
        assertEquals("Claudroid backup 2024-01-02 03:04", GitHubTarget.message(at))
    }

    @Test
    fun `label names the repository`() {
        assertEquals("GitHub · owner/name", GitHubTarget.label(GithubConfig(repo = " owner/name ")))
    }
}
