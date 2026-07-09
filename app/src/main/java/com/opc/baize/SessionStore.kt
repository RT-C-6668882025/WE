package com.opc.baize

import com.opc.baize.core.Session
import com.opc.baize.core.SessionCodec
import java.io.File

/**
 * 会话历史文件仓库（开源版）：sessions.json 存 App 内部目录。
 *
 * - load()：优先读 sessions.json；不存在则尝试迁移旧版 pages.json（折成一个历史会话），
 *   迁移后写入 sessions.json，旧文件保留不动（安全起见不删）。任何异常 → 空列表。
 * - save()：整表覆盖写。
 *
 * 编解码走纯逻辑 SessionCodec（JVM 单测覆盖），这里只管文件 IO 与旧档迁移。
 */
class SessionStore(
    private val file: File,
    private val legacyPagesFile: File,
) {

    fun save(sessions: List<Session>) {
        try {
            file.writeText(SessionCodec.encode(sessions))
        } catch (t: Throwable) {
            // 存档失败不挡使用
        }
    }

    fun load(): List<Session> {
        return try {
            if (file.exists()) {
                SessionCodec.decode(file.readText())
            } else if (legacyPagesFile.exists()) {
                // 一次性迁移旧版整本书 → 一个历史会话
                val migrated = SessionCodec.fromLegacyPages(
                    legacyPagesFile.readText(),
                    sessionId = "s_legacy_${legacyPagesFile.lastModified()}",
                    createdAt = legacyPagesFile.lastModified(),
                )
                if (migrated.isNotEmpty()) save(migrated)
                migrated
            } else {
                emptyList()
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun clear() {
        try { file.delete() } catch (t: Throwable) {}
    }
}
