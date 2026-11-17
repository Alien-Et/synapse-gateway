package io.synapse.core.store

import java.io.File

/**
 * 极简键值存储。Android 侧用加密实现，命令行侧用 [FileKeyValueStore]。
 * 之所以抽成接口，是为了让凭证在两个平台上都只走一条读写路径。
 */
interface KeyValueStore {
    fun read(key: String): String?

    /**
     * 写一个键，返回是否真的落盘成功。
     *
     * 返回布尔值而不是 Unit：Android 侧的加密存储在 commit 失败（磁盘满 / IO 错误）时
     * 不会抛异常，只会返回 false。过去的实现把返回值丢掉，于是「凭证刷新后写回失败」
     * 完全无声 —— 用户下次启动拿的是已作废的 refreshToken，表现为「登录又失效了」。
     */
    fun write(key: String, value: String): Boolean

    /**
     * 一次事务写多个键，返回是否全部成功。
     *
     * 默认逐条写（兼容所有实现）；加密存储可覆盖成一个 `commit()` 批量提交，
     * 避免「一笔调用记录要连续 4~5 次同步写盘」——那是高频请求下最明显的 I/O 放大点。
     */
    fun writeBatch(entries: Map<String, String>): Boolean {
        var allOk = true
        for ((key, value) in entries) {
            if (!write(key, value)) allOk = false
        }
        return allOk
    }

    fun delete(key: String)
    /** 返回该前缀下的全部键。 */
    fun keys(prefix: String): List<String>
}

/** 以文件落盘的实现：键里的 `/` 会映射成子目录。 */
class FileKeyValueStore(private val root: File) : KeyValueStore {

    init {
        if (!root.exists()) root.mkdirs()
    }

    override fun read(key: String): String? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    override fun write(key: String, value: String): Boolean {
        val file = fileFor(key)
        file.parentFile?.mkdirs()
        // 先写临时文件再改名，避免中途失败留下半截凭证
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(value)
        if (file.exists() && !file.delete()) {
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return false
        }
        return true
    }

    override fun delete(key: String) {
        val file = fileFor(key)
        if (file.isFile) file.delete()
    }

    override fun keys(prefix: String): List<String> {
        val base = root.absolutePath
        return root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { it.absolutePath.removePrefix(base).trimStart(File.separatorChar).replace(File.separatorChar, '/') }
            .filter { it.startsWith(prefix) }
            .toList()
    }

    private fun fileFor(key: String): File = File(root, key)
}
