package app.claudroid.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Config validation runs only when an action starts (docs/BACKUP.md §4). */
class BackupConfigTest {

    private fun failure(config: BackupConfig): String =
        assertThrows(IllegalStateException::class.java) { config.requireUsable() }.message.orEmpty()

    @Test
    fun `local mode points at export and import`() {
        assertEquals(
            "当前目标是本地导出/导入，请改用本地备份或恢复",
            failure(BackupConfig(kind = BackupKind.LOCAL)),
        )
    }

    @Test
    fun `webdav requires a complete http url without embedded credentials`() {
        fun error(url: String): String =
            failure(BackupConfig(kind = BackupKind.WEBDAV, webdav = WebDavConfig(url = url)))

        assertEquals("请填写 WebDAV 备份地址", error(""))
        assertEquals("WebDAV 地址必须以 http:// 或 https:// 开头", error("ftp://h/f.zip"))
        assertEquals("WebDAV 地址缺少主机名", error("https:///f.zip"))
        assertEquals(
            "WebDAV 地址不能包含用户名或密码，请填写下方字段",
            error("https://user:pw@dav.example.com/f.zip"),
        )
        assertEquals(
            "https://dav.example.com/f.zip",
            BackupConfig(
                kind = BackupKind.WEBDAV,
                webdav = WebDavConfig(url = "https://dav.example.com/f.zip"),
            ).requireUsable().webdav.url,
        )
    }

    @Test
    fun `s3 requires bucket region and both keys`() {
        val base = S3Config(bucket = "bucket", region = "us-east-1", accessKey = "a", secretKey = "s")
        assertEquals("请填写 S3 Bucket", failure(BackupConfig(kind = BackupKind.S3, s3 = base.copy(bucket = ""))))
        assertEquals("请填写 S3 Region", failure(BackupConfig(kind = BackupKind.S3, s3 = base.copy(region = " "))))
        assertEquals("请填写 S3 Access Key", failure(BackupConfig(kind = BackupKind.S3, s3 = base.copy(accessKey = ""))))
        assertEquals("请填写 S3 Secret Key", failure(BackupConfig(kind = BackupKind.S3, s3 = base.copy(secretKey = ""))))
        assertEquals(
            "S3 Bucket 名称无效",
            failure(BackupConfig(kind = BackupKind.S3, s3 = base.copy(bucket = "bad bucket"))),
        )
        assertEquals(
            "S3 Endpoint 必须以 http:// 或 https:// 开头并包含主机名",
            failure(BackupConfig(kind = BackupKind.S3, s3 = base.copy(endpoint = "ftp://h"))),
        )
        // A bare host:port is accepted and normalized later by S3Target.
        BackupConfig(kind = BackupKind.S3, s3 = base.copy(endpoint = "127.0.0.1:9000")).requireUsable()
    }

    @Test
    fun `github requires owner slash name and a token`() {
        val base = GithubConfig(repo = "owner/name", token = "t")
        assertEquals(
            "请填写 GitHub 仓库（owner/name）",
            failure(BackupConfig(kind = BackupKind.GITHUB, github = base.copy(repo = ""))),
        )
        assertEquals(
            "GitHub 仓库格式应为 owner/name",
            failure(BackupConfig(kind = BackupKind.GITHUB, github = base.copy(repo = "owner"))),
        )
        assertEquals(
            "请填写 GitHub Token",
            failure(BackupConfig(kind = BackupKind.GITHUB, github = base.copy(token = ""))),
        )
        assertEquals(
            "GitHub 文件路径无效",
            failure(BackupConfig(kind = BackupKind.GITHUB, github = base.copy(path = "../escape.zip"))),
        )
        BackupConfig(kind = BackupKind.GITHUB, github = base).requireUsable()
    }
}
